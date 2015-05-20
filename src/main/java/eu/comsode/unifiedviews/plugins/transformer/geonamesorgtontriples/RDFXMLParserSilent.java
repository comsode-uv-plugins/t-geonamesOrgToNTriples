package eu.comsode.unifiedviews.plugins.transformer.geonamesorgtontriples;

import info.aduna.net.ParsedURI;
import info.aduna.xml.XMLReaderFactory;
import info.aduna.xml.XMLUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Stack;

import javax.xml.transform.sax.SAXResult;

import org.apache.commons.io.input.BOMInputStream;
import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RioSetting;
import org.openrdf.rio.helpers.RDFParserBase;
import org.openrdf.rio.helpers.XMLParserSettings;
import org.openrdf.rio.rdfxml.RDFXMLParser;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

public class RDFXMLParserSilent extends RDFXMLParser {

    /*-----------*
     * Variables *
     *-----------*/

    /**
     * A filter filtering calls to SAX methods specifically for this parser.
     */
    private SAXFilter saxFilter;

    /**
     * The base URI of the document. This variable is set when <tt>parse(inputStream, baseURI)</tt> is called and will not be changed
     * during parsing.
     */
    private String documentURI;

    /**
     * The language of literal values as can be specified using xml:lang
     * attributes. This variable is set/modified by the SAXFilter during parsing
     * such that it always represents the language of the context in which
     * elements are reported.
     */
    private String xmlLang;

    /**
     * A stack of node- and property elements.
     */
    private Stack<Object> elementStack = new Stack<Object>();

    /**
     * A set containing URIs that have been generated as a result of rdf:ID
     * attributes. These URIs should be unique within a single document.
     */
    private Set<URI> usedIDs = new HashSet<URI>();

    protected XMLReader xmlReader;

    /*--------------*
     * Constructors *
     *--------------*/

    /**
     * Creates a new RDFXMLParser that will use a {@link ValueFactoryImpl} to
     * create RDF model objects.
     */
    public RDFXMLParserSilent() {
        super();

        // SAXFilter does some filtering and verifying of SAX events
        saxFilter = new SAXFilter(this);
    }

    public void initialize() throws SAXException {
        if (getParserConfig().isSet(XMLParserSettings.CUSTOM_XML_READER)) {
            xmlReader = getParserConfig().get(XMLParserSettings.CUSTOM_XML_READER);
        }
        else {
            xmlReader = XMLReaderFactory.createXMLReader();
        }

        xmlReader.setContentHandler(saxFilter);
        xmlReader.setErrorHandler(this);

        // Set all compulsory feature settings, using the defaults if they are
        // not explicitly set
        for (RioSetting<Boolean> aSetting : getCompulsoryXmlFeatureSettings()) {
            try {
                xmlReader.setFeature(aSetting.getKey(), getParserConfig().get(aSetting));
            } catch (SAXNotRecognizedException e) {
                reportWarning(String.format("%s is not a recognized SAX feature.", aSetting.getKey()));
            } catch (SAXNotSupportedException e) {
                reportWarning(String.format("%s is not a supported SAX feature.", aSetting.getKey()));
            }
        }

        // Set all compulsory property settings, using the defaults if they are
        // not explicitly set
        for (RioSetting<?> aSetting : getCompulsoryXmlPropertySettings()) {
            try {
                xmlReader.setProperty(aSetting.getKey(), getParserConfig().get(aSetting));
            } catch (SAXNotRecognizedException e) {
                reportWarning(String.format("%s is not a recognized SAX property.", aSetting.getKey()));
            } catch (SAXNotSupportedException e) {
                reportWarning(String.format("%s is not a supported SAX property.", aSetting.getKey()));
            }
        }

        // Check for any optional feature settings that are explicitly set in
        // the parser config
        for (RioSetting<Boolean> aSetting : getOptionalXmlFeatureSettings()) {
            try {
                if (getParserConfig().isSet(aSetting)) {
                    xmlReader.setFeature(aSetting.getKey(), getParserConfig().get(aSetting));
                }
            } catch (SAXNotRecognizedException e) {
                reportWarning(String.format("%s is not a recognized SAX feature.", aSetting.getKey()));
            } catch (SAXNotSupportedException e) {
                reportWarning(String.format("%s is not a supported SAX feature.", aSetting.getKey()));
            }
        }

        // Check for any optional property settings that are explicitly set in
        // the parser config
        for (RioSetting<?> aSetting : getOptionalXmlPropertySettings()) {
            try {
                if (getParserConfig().isSet(aSetting)) {
                    xmlReader.setProperty(aSetting.getKey(), getParserConfig().get(aSetting));
                }
            } catch (SAXNotRecognizedException e) {
                reportWarning(String.format("%s is not a recognized SAX property.", aSetting.getKey()));
            } catch (SAXNotSupportedException e) {
                reportWarning(String.format("%s is not a supported SAX property.", aSetting.getKey()));
            }
        }

    }

    /**
     * Creates a new RDFXMLParser that will use the supplied <tt>ValueFactory</tt> to create RDF model objects.
     * 
     * @param valueFactory
     *            A ValueFactory.
     */
    public RDFXMLParserSilent(ValueFactory valueFactory) {
        super(valueFactory);

        // SAXFilter does some filtering and verifying of SAX events
        saxFilter = new SAXFilter(this);
    }

    /*---------*
     * Methods *
     *---------*/
    /**
     * Sets the parser in a mode to parse stand-alone RDF documents. In
     * stand-alone RDF documents, the enclosing <tt>rdf:RDF</tt> root element is
     * optional if this root element contains just one element (e.g. <tt>rdf:Description</tt>.
     */
    public void setParseStandAloneDocuments(boolean standAloneDocs) {
        getParserConfig().set(XMLParserSettings.PARSE_STANDALONE_DOCUMENTS, standAloneDocs);
    }

    /**
     * Returns whether the parser is currently in a mode to parse stand-alone RDF
     * documents.
     * 
     * @see #setParseStandAloneDocuments
     */
    public boolean getParseStandAloneDocuments() {
        return getParserConfig().get(XMLParserSettings.PARSE_STANDALONE_DOCUMENTS);
    }

    /**
     * Parses the data from the supplied InputStream, using the supplied baseURI
     * to resolve any relative URI references.
     * 
     * @param in
     *            The InputStream from which to read the data, must not be <tt>null</tt>.
     * @param baseURI
     *            The URI associated with the data in the InputStream, must not be <tt>null</tt>.
     * @throws IOException
     *             If an I/O error occurred while data was read from the InputStream.
     * @throws RDFParseException
     *             If the parser has found an unrecoverable parse error.
     * @throws RDFHandlerException
     *             If the configured statement handler encountered an unrecoverable
     *             error.
     * @throws IllegalArgumentException
     *             If the supplied input stream or base URI is <tt>null</tt>.
     */
    @Override
    public synchronized void parse(InputStream in, String baseURI)
            throws IOException, RDFParseException, RDFHandlerException
    {
        if (in == null) {
            throw new IllegalArgumentException("Input stream cannot be 'null'");
        }
        if (baseURI == null) {
            throw new IllegalArgumentException("Base URI cannot be 'null'");
        }

        InputSource inputSource = new InputSource(new BOMInputStream(in, false));
        inputSource.setSystemId(baseURI);

        parse(inputSource);
    }

    /**
     * Parses the data from the supplied Reader, using the supplied baseURI to
     * resolve any relative URI references.
     * 
     * @param reader
     *            The Reader from which to read the data, must not be <tt>null</tt>.
     * @param baseURI
     *            The URI associated with the data in the InputStream, must not be <tt>null</tt>.
     * @throws IOException
     *             If an I/O error occurred while data was read from the InputStream.
     * @throws RDFParseException
     *             If the parser has found an unrecoverable parse error.
     * @throws RDFHandlerException
     *             If the configured statement handler has encountered an
     *             unrecoverable error.
     * @throws IllegalArgumentException
     *             If the supplied reader or base URI is <tt>null</tt>.
     */
    @Override
    public synchronized void parse(Reader reader, String baseURI)
            throws IOException, RDFParseException, RDFHandlerException
    {
        if (reader == null) {
            throw new IllegalArgumentException("Reader cannot be 'null'");
        }
        if (baseURI == null) {
            throw new IllegalArgumentException("Base URI cannot be 'null'");
        }

        InputSource inputSource = new InputSource(reader);
        inputSource.setSystemId(baseURI);

        parse(inputSource);
    }

    private void parse(InputSource inputSource)
            throws IOException, RDFParseException, RDFHandlerException
    {
        try {
            documentURI = inputSource.getSystemId();

            saxFilter.setParseStandAloneDocuments(getParserConfig().get(
                    XMLParserSettings.PARSE_STANDALONE_DOCUMENTS));

            // saxFilter.clear();
            saxFilter.setDocumentURI(documentURI);
            xmlReader.parse(inputSource);
        } catch (SAXParseException e) {
            Exception wrappedExc = e.getException();

            if (wrappedExc == null) {
                reportFatalError(e, e.getLineNumber(), e.getColumnNumber());
            }
            else {
                reportFatalError(wrappedExc, e.getLineNumber(), e.getColumnNumber());
            }
        } catch (SAXException e) {
            Exception wrappedExc = e.getException();

            if (wrappedExc == null) {
                reportFatalError(e);
            }
            else if (wrappedExc instanceof RDFParseException) {
                throw (RDFParseException) wrappedExc;
            }
            else if (wrappedExc instanceof RDFHandlerException) {
                throw (RDFHandlerException) wrappedExc;
            }
            else {
                reportFatalError(wrappedExc);
            }
        } finally {
            // Clean up
            saxFilter.clear();
            xmlLang = null;
            elementStack.clear();
            usedIDs.clear();
            clear();
        }
    }

    /**
     * Returns a collection of settings that will always be set as XML parser
     * properties using {@link XMLReader#setProperty(String, Object)}
     * <p>
     * Subclasses can override this to specify more supported settings.
     * 
     * @return A collection of {@link RioSetting}s that indicate which properties
     *         will always be setup using {@link XMLReader#setProperty(String, Object)}.
     */
    public Collection<RioSetting<?>> getCompulsoryXmlPropertySettings() {
        return Collections.<RioSetting<?>> emptyList();
    }

    /**
     * Returns a collection of settings that will always be set as XML parser
     * features using {@link XMLReader#setFeature(String, boolean)}.
     * <p>
     * Subclasses can override this to specify more supported settings.
     * 
     * @return A collection of {@link RioSetting}s that indicate which boolean
     *         settings will always be setup using {@link XMLReader#setFeature(String, boolean)}.
     */
    public Collection<RioSetting<Boolean>> getCompulsoryXmlFeatureSettings() {
        Set<RioSetting<Boolean>> results = new HashSet<RioSetting<Boolean>>();
        results.add(XMLParserSettings.SECURE_PROCESSING);
        return results;
    }

    /**
     * Returns a collection of settings that will be used, if set in {@link #getParserConfig()}, as XML parser properties using
     * {@link XMLReader#setProperty(String, Object)}
     * <p>
     * Subclasses can override this to specify more supported settings.
     * 
     * @return A collection of {@link RioSetting}s that indicate which properties
     *         can be setup using {@link XMLReader#setProperty(String, Object)}.
     */
    public Collection<RioSetting<?>> getOptionalXmlPropertySettings() {
        return Collections.<RioSetting<?>> emptyList();
    }

    /**
     * Returns a collection of settings that will be used, if set in {@link #getParserConfig()}, as XML parser features using
     * {@link XMLReader#setFeature(String, boolean)}.
     * <p>
     * Subclasses can override this to specify more supported settings.
     * 
     * @return A collection of {@link RioSetting}s that indicate which boolean
     *         settings can be setup using {@link XMLReader#setFeature(String, boolean)}.
     */
    public Collection<RioSetting<Boolean>> getOptionalXmlFeatureSettings() {
        Set<RioSetting<Boolean>> results = new HashSet<RioSetting<Boolean>>();
        results.add(XMLParserSettings.LOAD_EXTERNAL_DTD);
        return results;
    }

    @Override
    public Collection<RioSetting<?>> getSupportedSettings() {
        // Override to add RDF/XML specific supported settings
        Set<RioSetting<?>> results = new HashSet<RioSetting<?>>(super.getSupportedSettings());

        results.addAll(getCompulsoryXmlPropertySettings());
        results.addAll(getCompulsoryXmlFeatureSettings());
        results.addAll(getOptionalXmlPropertySettings());
        results.addAll(getOptionalXmlFeatureSettings());

        results.add(XMLParserSettings.CUSTOM_XML_READER);
        results.add(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID);
        results.add(XMLParserSettings.FAIL_ON_INVALID_NCNAME);
        results.add(XMLParserSettings.FAIL_ON_INVALID_QNAME);
        results.add(XMLParserSettings.FAIL_ON_MISMATCHED_TAGS);
        results.add(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
        results.add(XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS);
        results.add(XMLParserSettings.PARSE_STANDALONE_DOCUMENTS);

        return results;
    }

    public SAXResult getSAXResult(String baseURI) {
        if (baseURI == null) {
            throw new IllegalArgumentException("Base URI cannot be 'null'");
        }
        documentURI = baseURI;
        saxFilter.setDocumentURI(baseURI);

        return new SAXResult(saxFilter);
    }

    void startDocument()
            throws RDFParseException, RDFHandlerException
    {
        if (rdfHandler != null) {
            rdfHandler.startRDF();
        }
    }

    void endDocument()
            throws RDFParseException, RDFHandlerException
    {
        if (rdfHandler != null) {
            rdfHandler.endRDF();
        }
    }

    /*-----------------------------*
     * Methods called by SAXFilter *
     *-----------------------------*/

    @Override
    protected void setBaseURI(ParsedURI baseURI) {
        // Note: we need to override this method to allow SAXFilter to access it
        super.setBaseURI(baseURI);
    }

    void setXMLLang(String xmlLang) {
        if ("".equals(xmlLang)) {
            this.xmlLang = null;
        }
        else {
            this.xmlLang = xmlLang;
        }
    }

    void startElement(String namespaceURI, String localName, String qName, Atts atts)
            throws RDFParseException, RDFHandlerException
    {
        if (topIsProperty()) {
            // this element represents the subject and/or object of a statement
            processNodeElt(namespaceURI, localName, qName, atts, false);
        }
        else {
            // this element represents a property
            processPropertyElt(namespaceURI, localName, qName, atts, false);
        }
    }

    void endElement(String namespaceURI, String localName, String qName)
            throws RDFParseException, RDFHandlerException
    {
        Object topElement = peekStack(0);

        if (topElement instanceof NodeElement) {
            // Check if top node is 'volatile', meaning that it doesn't have a
            // start- and end element associated with it.
            if (((NodeElement) topElement).isVolatile()) {
                elementStack.pop();
            }
        }
        else {
            // topElement instanceof PropertyElement
            PropertyElement predicate = (PropertyElement) topElement;

            if (predicate.parseCollection()) {
                Resource lastListResource = predicate.getLastListResource();

                if (lastListResource == null) {
                    // no last list resource, list must have been empty.
                    NodeElement subject = (NodeElement) peekStack(1);

                    reportStatement(subject.getResource(), predicate.getURI(), RDF.NIL);

                    handleReification(RDF.NIL);
                }
                else {
                    // Generate the final tail of the list.
                    reportStatement(lastListResource, RDF.REST, RDF.NIL);
                }
            }

        }

        elementStack.pop();
    }

    void emptyElement(String namespaceURI, String localName, String qName, Atts atts)
            throws RDFParseException, RDFHandlerException
    {
        if (topIsProperty()) {
            // this element represents the subject and/or object of a statement
            processNodeElt(namespaceURI, localName, qName, atts, true);
        }
        else {
            // this element represents a property
            processPropertyElt(namespaceURI, localName, qName, atts, true);
        }
    }

    void text(String text)
            throws RDFParseException, RDFHandlerException
    {
        if (!topIsProperty()) {
            reportError("unexpected literal", XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
            return;
        }

        PropertyElement propEl = (PropertyElement) peekStack(0);
        URI datatype = propEl.getDatatype();

        Literal lit = createLiteral(text, xmlLang, datatype);

        NodeElement subject = (NodeElement) peekStack(1);
        PropertyElement predicate = (PropertyElement) peekStack(0);

        reportStatement(subject.getResource(), predicate.getURI(), lit);

        handleReification(lit);
    }

    /*------------------------*
     * RDF processing methods *
     *------------------------*/

    /* Process a node element (can be both subject and object) */
    private void processNodeElt(String namespaceURI, String localName, String qName, Atts atts,
            boolean isEmptyElt)
            throws RDFParseException, RDFHandlerException
    {
        if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
            // Check the element name
            checkNodeEltName(namespaceURI, localName, qName);
        }

        Resource nodeResource = getNodeResource(atts);
        NodeElement nodeElement = new NodeElement(nodeResource);

        if (!elementStack.isEmpty()) {
            // node can be object of a statement, or part of an rdf:List
            NodeElement subject = (NodeElement) peekStack(1);
            PropertyElement predicate = (PropertyElement) peekStack(0);

            if (predicate.parseCollection()) {
                Resource lastListRes = predicate.getLastListResource();
                BNode newListRes = createBNode();

                if (lastListRes == null) {
                    // first element in the list
                    reportStatement(subject.getResource(), predicate.getURI(), newListRes);

                    handleReification(newListRes);
                }
                else {
                    // not the first element in the list
                    reportStatement(lastListRes, RDF.REST, newListRes);
                }

                reportStatement(newListRes, RDF.FIRST, nodeResource);

                predicate.setLastListResource(newListRes);
            }
            else {
                reportStatement(subject.getResource(), predicate.getURI(), nodeResource);

                handleReification(nodeResource);
            }
        }

        if (!localName.equals("Description") || !namespaceURI.equals(RDF.NAMESPACE)) {
            // element name is uri's type
            URI className = null;
            if ("".equals(namespaceURI)) {
                // No namespace, use base URI
                className = buildResourceFromLocalName(localName);
            }
            else {
                className = createURI(namespaceURI + localName);
            }
            reportStatement(nodeResource, RDF.TYPE, className);
        }

        Att type = atts.removeAtt(RDF.NAMESPACE, "type");
        if (type != null) {
            // rdf:type attribute, value is a URI-reference
            URI className = resolveURI(type.getValue());

            reportStatement(nodeResource, RDF.TYPE, className);
        }

        if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
            checkRDFAtts(atts);
        }

        processSubjectAtts(nodeElement, atts);

        if (!isEmptyElt) {
            elementStack.push(nodeElement);
        }
    }

    /**
     * Retrieves the resource of a node element (subject or object) using
     * relevant attributes (rdf:ID, rdf:about and rdf:nodeID) from its attributes
     * list.
     * 
     * @return a resource or a bNode.
     */
    private Resource getNodeResource(Atts atts)
            throws RDFParseException
    {
        Att id = atts.removeAtt(RDF.NAMESPACE, "ID");
        Att about = atts.removeAtt(RDF.NAMESPACE, "about");
        Att nodeID = atts.removeAtt(RDF.NAMESPACE, "nodeID");

        if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
            int definedAttsCount = 0;

            if (id != null) {
                definedAttsCount++;
            }
            if (about != null) {
                definedAttsCount++;
            }
            if (nodeID != null) {
                definedAttsCount++;
            }

            if (definedAttsCount > 1) {
                reportError("Only one of the attributes rdf:ID, rdf:about or rdf:nodeID can be used here",
                        XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
            }
        }

        Resource result = null;

        if (id != null) {
            result = buildURIFromID(id.getValue());
        }
        else if (about != null) {
            result = resolveURI(about.getValue());
        }
        else if (nodeID != null) {
            result = createBNode(nodeID.getValue());
        }
        else {
            // No resource specified, generate a bNode
            result = createBNode();
        }

        return result;
    }

    /** processes subject attributes. */
    private void processSubjectAtts(NodeElement nodeElt, Atts atts)
            throws RDFParseException, RDFHandlerException
    {
        Resource subject = nodeElt.getResource();

        Iterator<Att> iter = atts.iterator();

        while (iter.hasNext()) {
            Att att = iter.next();

            URI predicate = createURI(att.getURI());
            Literal lit = createLiteral(att.getValue(), xmlLang, null);

            reportStatement(subject, predicate, lit);
        }
    }

    private void processPropertyElt(String namespaceURI, String localName, String qName, Atts atts,
            boolean isEmptyElt)
            throws RDFParseException, RDFHandlerException
    {
        if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
            checkPropertyEltName(namespaceURI, localName, qName,
                    XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
        }

        // Get the URI of the property
        URI propURI = null;
        if (namespaceURI.equals("")) {
            // no namespace URI
            reportError("unqualified property element <" + qName + "> not allowed",
                    XMLParserSettings.FAIL_ON_INVALID_QNAME);
            // Use base URI as namespace:
            propURI = buildResourceFromLocalName(localName);
        }
        else {
            propURI = createURI(namespaceURI + localName);
        }

        // List expansion rule
        if (propURI.equals(RDF.LI)) {
            NodeElement subject = (NodeElement) peekStack(0);
            propURI = createURI(RDF.NAMESPACE + "_" + subject.getNextLiCounter());
        }

        // Push the property on the stack.
        PropertyElement predicate = new PropertyElement(propURI);
        elementStack.push(predicate);

        // Check if property has a reification ID
        Att id = atts.removeAtt(RDF.NAMESPACE, "ID");
        if (id != null) {
            URI reifURI = buildURIFromID(id.getValue());
            predicate.setReificationURI(reifURI);
        }

        // Check for presence of rdf:parseType attribute
        Att parseType = atts.removeAtt(RDF.NAMESPACE, "parseType");

        if (parseType != null) {
            if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
                checkNoMoreAtts(atts);
            }

            String parseTypeValue = parseType.getValue();

            if (parseTypeValue.equals("Resource")) {
                BNode objectResource = createBNode();
                NodeElement subject = (NodeElement) peekStack(1);

                reportStatement(subject.getResource(), propURI, objectResource);

                if (isEmptyElt) {
                    handleReification(objectResource);
                }
                else {
                    NodeElement object = new NodeElement(objectResource);
                    object.setIsVolatile(true);
                    elementStack.push(object);
                }
            }
            else if (parseTypeValue.equals("Collection")) {
                if (isEmptyElt) {
                    NodeElement subject = (NodeElement) peekStack(1);
                    reportStatement(subject.getResource(), propURI, RDF.NIL);
                    handleReification(RDF.NIL);
                }
                else {
                    predicate.setParseCollection(true);
                }
            }
            else {
                // other parseType
                if (!parseTypeValue.equals("Literal")) {
                    reportWarning("unknown parseType: " + parseType.getValue());
                }

                if (isEmptyElt) {
                    NodeElement subject = (NodeElement) peekStack(1);

                    Literal lit = createLiteral("", null, RDF.XMLLITERAL);

                    reportStatement(subject.getResource(), propURI, lit);

                    handleReification(lit);
                }
                else {
                    // The next string is an rdf:XMLLiteral
                    predicate.setDatatype(RDF.XMLLITERAL);

                    saxFilter.setParseLiteralMode();
                }
            }
        }
        // parseType == null
        else if (isEmptyElt) {
            // empty element without an rdf:parseType attribute

            // Note: we handle rdf:datatype attributes here to allow datatyped
            // empty strings in documents. The current spec does have a
            // production rule that matches this, which is likely to be an
            // omission on its part.
            Att datatype = atts.getAtt(RDF.NAMESPACE, "datatype");

            if (atts.size() == 0 || atts.size() == 1 && datatype != null) {
                // element had no attributes, or only the optional
                // rdf:ID and/or rdf:datatype attributes.
                NodeElement subject = (NodeElement) peekStack(1);

                URI dtURI = null;
                if (datatype != null) {
                    dtURI = createURI(datatype.getValue());
                }

                Literal lit = createLiteral("", xmlLang, dtURI);

                reportStatement(subject.getResource(), propURI, lit);
                handleReification(lit);
            }
            else {
                // Create resource for the statement's object.
                Resource resourceRes = getPropertyResource(atts);

                // All special rdf attributes have been checked/removed.
                if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
                    checkRDFAtts(atts);
                }

                NodeElement resourceElt = new NodeElement(resourceRes);
                NodeElement subject = (NodeElement) peekStack(1);

                reportStatement(subject.getResource(), propURI, resourceRes);
                handleReification(resourceRes);

                Att type = atts.removeAtt(RDF.NAMESPACE, "type");
                if (type != null) {
                    // rdf:type attribute, value is a URI-reference
                    URI className = resolveURI(type.getValue());

                    reportStatement(resourceRes, RDF.TYPE, className);
                }

                processSubjectAtts(resourceElt, atts);
            }
        }
        else {
            // Not an empty element, sub elements will follow.

            // Check for rdf:datatype attribute
            Att datatype = atts.removeAtt(RDF.NAMESPACE, "datatype");
            if (datatype != null) {
                URI dtURI = resolveURI(datatype.getValue());
                predicate.setDatatype(dtURI);
            }

            // No more attributes are expected.
            if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
                checkNoMoreAtts(atts);
            }
        }

        if (isEmptyElt) {
            // Empty element has been pushed on the stack
            // at the start of this method, remove it.
            elementStack.pop();
        }
    }

    /**
     * Retrieves the object resource of a property element using relevant
     * attributes (rdf:resource and rdf:nodeID) from its attributes list.
     * 
     * @return a resource or a bNode.
     */
    private Resource getPropertyResource(Atts atts)
            throws RDFParseException
    {
        Att resource = atts.removeAtt(RDF.NAMESPACE, "resource");
        Att nodeID = atts.removeAtt(RDF.NAMESPACE, "nodeID");

        if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
            int definedAttsCount = 0;

            if (resource != null) {
                definedAttsCount++;
            }
            if (nodeID != null) {
                definedAttsCount++;
            }

            if (definedAttsCount > 1) {
                reportError("Only one of the attributes rdf:resource or rdf:nodeID can be used here",
                        XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
            }
        }

        Resource result = null;

        if (resource != null) {
            result = resolveURI(resource.getValue());
        }
        else if (nodeID != null) {
            result = createBNode(nodeID.getValue());
        }
        else {
            // No resource specified, generate a bNode
            result = createBNode();
        }

        return result;
    }

    /*
     * Processes any rdf:ID attributes that generate reified statements. This
     * method assumes that a PropertyElement (which can have an rdf:ID attribute)
     * is on top of the stack, and a NodeElement is below that.
     */
    private void handleReification(Value value)
            throws RDFParseException, RDFHandlerException
    {
        PropertyElement predicate = (PropertyElement) peekStack(0);

        if (predicate.isReified()) {
            NodeElement subject = (NodeElement) peekStack(1);
            URI reifRes = predicate.getReificationURI();
            reifyStatement(reifRes, subject.getResource(), predicate.getURI(), value);
        }
    }

    private void reifyStatement(Resource reifNode, Resource subj, URI pred, Value obj)
            throws RDFParseException, RDFHandlerException
    {
        reportStatement(reifNode, RDF.TYPE, RDF.STATEMENT);
        reportStatement(reifNode, RDF.SUBJECT, subj);
        reportStatement(reifNode, RDF.PREDICATE, pred);
        reportStatement(reifNode, RDF.OBJECT, obj);
    }

    /**
     * Builds a Resource from a non-qualified localname.
     */
    private URI buildResourceFromLocalName(String localName)
            throws RDFParseException
    {
        return resolveURI("#" + localName);
    }

    /**
     * Builds a Resource from the value of an rdf:ID attribute.
     */
    private URI buildURIFromID(String id)
            throws RDFParseException
    {
        if (getParserConfig().get(XMLParserSettings.FAIL_ON_INVALID_NCNAME)) {
            // Check if 'id' is a legal NCName
            if (!XMLUtil.isNCName(id)) {
                reportError("Not an XML Name: " + id, XMLParserSettings.FAIL_ON_INVALID_NCNAME);
            }
        }

        URI uri = resolveURI("#" + id);

        if (getParserConfig().get(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID)) {
            // ID (URI) should be unique in the current document
            if (!usedIDs.add(uri)) {
                // URI was not added because the set already contained an equal
                // strings
                reportError("ID '" + id + "' has already been defined",
                        XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID);
            }
        }

        return uri;
    }

    @Override
    protected BNode createBNode(String nodeID)
            throws RDFParseException
    {
        if (getParserConfig().get(XMLParserSettings.FAIL_ON_INVALID_NCNAME)) {
            // Check if 'nodeID' is a legal NCName
            if (!XMLUtil.isNCName(nodeID)) {
                reportError("Not an XML Name: " + nodeID, XMLParserSettings.FAIL_ON_INVALID_NCNAME);
            }
        }

        return super.createBNode(nodeID);
    }

    private Object peekStack(int distFromTop) {
        return elementStack.get(elementStack.size() - 1 - distFromTop);
    }

    private boolean topIsProperty() {
        return elementStack.isEmpty() || peekStack(0) instanceof PropertyElement;
    }

    /**
     * Checks whether the node element name is from the RDF namespace and, if so,
     * if it is allowed to be used in a node element. If the name is equal to one
     * of the disallowed names (RDF, ID, about, parseType, resource, nodeID,
     * datatype and li), an error is generated. If the name is not defined in the
     * RDF namespace, but it claims that it is from this namespace, a warning is
     * generated.
     */
    private void checkNodeEltName(String namespaceURI, String localName, String qName)
            throws RDFParseException
    {
        if (RDF.NAMESPACE.equals(namespaceURI)) {

            if (localName.equals("Description") || localName.equals("Seq") || localName.equals("Bag")
                    || localName.equals("Alt") || localName.equals("Statement") || localName.equals("Property")
                    || localName.equals("List") || localName.equals("subject") || localName.equals("predicate")
                    || localName.equals("object") || localName.equals("type") || localName.equals("value")
                    || localName.equals("first") || localName.equals("rest") || localName.equals("nil")
                    || localName.startsWith("_"))
            {
                // These are OK
            }
            else if (localName.equals("li") || localName.equals("RDF") || localName.equals("ID")
                    || localName.equals("about") || localName.equals("parseType") || localName.equals("resource")
                    || localName.equals("nodeID") || localName.equals("datatype"))
            {
                reportError("<" + qName + "> not allowed as node element",
                        XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
            }
            else if (localName.equals("bagID") || localName.equals("aboutEach")
                    || localName.equals("aboutEachPrefix"))
            {
                reportError(qName + " is no longer a valid RDF name",
                        XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
            }
            else {
                reportWarning("unknown rdf element <" + qName + ">");
            }
        }
    }

    /**
     * Checks whether the property element name is from the RDF namespace and, if
     * so, if it is allowed to be used in a property element. If the name is
     * equal to one of the disallowed names (RDF, ID, about, parseType, resource
     * and li), an error is generated. If the name is not defined in the RDF
     * namespace, but it claims that it is from this namespace, a warning is
     * generated.
     * 
     * @param setting
     */
    private void checkPropertyEltName(String namespaceURI, String localName, String qName,
            RioSetting<Boolean> setting)
            throws RDFParseException
    {
        if (RDF.NAMESPACE.equals(namespaceURI)) {

            if (localName.equals("li") || localName.equals("Seq") || localName.equals("Bag")
                    || localName.equals("Alt") || localName.equals("Statement") || localName.equals("Property")
                    || localName.equals("List") || localName.equals("subject") || localName.equals("predicate")
                    || localName.equals("object") || localName.equals("type") || localName.equals("value")
                    || localName.equals("first") || localName.equals("rest") || localName.equals("nil")
                    || localName.startsWith("_"))
            {
                // These are OK
            }
            else if (localName.equals("Description") || localName.equals("RDF") || localName.equals("ID")
                    || localName.equals("about") || localName.equals("parseType") || localName.equals("resource")
                    || localName.equals("nodeID") || localName.equals("datatype"))
            {
                reportError("<" + qName + "> not allowed as property element", setting);
            }
            else if (localName.equals("bagID") || localName.equals("aboutEach")
                    || localName.equals("aboutEachPrefix"))
            {
                reportError(qName + " is no longer a valid RDF name", setting);
            }
            else {
                reportWarning("unknown rdf element <" + qName + ">");
            }
        }
    }

    /**
     * Checks whether 'atts' contains attributes from the RDF namespace that are
     * not allowed as attributes. If such an attribute is found, an error is
     * generated and the attribute is removed from 'atts'. If the attribute is
     * not defined in the RDF namespace, but it claims that it is from this
     * namespace, a warning is generated.
     */
    private void checkRDFAtts(Atts atts)
            throws RDFParseException
    {
        Iterator<Att> iter = atts.iterator();

        while (iter.hasNext()) {
            Att att = iter.next();

            if (RDF.NAMESPACE.equals(att.getNamespace())) {
                String localName = att.getLocalName();

                if (localName.equals("Seq") || localName.equals("Bag") || localName.equals("Alt")
                        || localName.equals("Statement") || localName.equals("Property")
                        || localName.equals("List") || localName.equals("subject") || localName.equals("predicate")
                        || localName.equals("object") || localName.equals("type") || localName.equals("value")
                        || localName.equals("first") || localName.equals("rest") || localName.equals("nil")
                        || localName.startsWith("_"))
                {
                    // These are OK
                }
                else if (localName.equals("Description") || localName.equals("li") || localName.equals("RDF")
                        || localName.equals("ID") || localName.equals("about") || localName.equals("parseType")
                        || localName.equals("resource") || localName.equals("nodeID")
                        || localName.equals("datatype"))
                {
                    reportError("'" + att.getQName() + "' not allowed as attribute name",
                            XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
                    iter.remove();
                }
                else if (localName.equals("bagID") || localName.equals("aboutEach")
                        || localName.equals("aboutEachPrefix"))
                {
                    reportError(att.getQName() + " is no longer a valid RDF name",
                            XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
                }
                else {
                    reportWarning("unknown rdf attribute '" + att.getQName() + "'");
                }
            }
        }
    }

    /**
     * Checks whether 'atts' is empty. If this is not the case, a warning is
     * generated for each attribute that is still present.
     */
    private void checkNoMoreAtts(Atts atts)
            throws RDFParseException
    {
        if (atts.size() > 0) {
            Iterator<Att> iter = atts.iterator();

            while (iter.hasNext()) {
                Att att = iter.next();

                reportError("unexpected attribute '" + att.getQName() + "'",
                        XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
                iter.remove();
            }
        }
    }

    /**
     * Reports a stament to the configured RDFHandlerException.
     * 
     * @param subject
     *            The statement's subject.
     * @param predicate
     *            The statement's predicate.
     * @param object
     *            The statement's object.
     * @throws RDFHandlerException
     *             If the configured RDFHandlerException throws an
     *             RDFHandlerException.
     */
    private void reportStatement(Resource subject, URI predicate, Value object)
            throws RDFParseException, RDFHandlerException
    {
        Statement st = createStatement(subject, predicate, object);
        if (rdfHandler != null) {
            rdfHandler.handleStatement(st);
        }
    }

    @Override
    protected Literal createLiteral(String label, String lang, URI datatype)
            throws RDFParseException
    {
        Locator locator = saxFilter.getLocator();
        if (locator != null) {
            return createLiteral(label, lang, datatype, locator.getLineNumber(), locator.getColumnNumber());
        }
        else {
            return createLiteral(label, lang, datatype, -1, -1);
        }
    }

    /**
     * Overrides {@link RDFParserBase#reportWarning(String)}, adding line- and
     * column number information to the error.
     */
    @Override
    protected void reportWarning(String msg) {
        Locator locator = saxFilter.getLocator();
        if (locator != null) {
            reportWarning(msg, locator.getLineNumber(), locator.getColumnNumber());
        }
        else {
            reportWarning(msg, -1, -1);
        }
    }

    /**
     * Overrides {@link RDFParserBase#reportError(String)}, adding line- and
     * column number information to the error.
     */
    @Override
    protected void reportError(String msg, RioSetting<Boolean> setting)
            throws RDFParseException
    {
        Locator locator = saxFilter.getLocator();
        if (locator != null) {
            reportError(msg, locator.getLineNumber(), locator.getColumnNumber(), setting);
        }
        else {
            reportError(msg, -1, -1, setting);
        }
    }

    /**
     * Overrides {@link RDFParserBase#reportFatalError(String)}, adding line- and
     * column number information to the error.
     */
    @Override
    protected void reportFatalError(String msg)
            throws RDFParseException
    {
        Locator locator = saxFilter.getLocator();
        if (locator != null) {
            reportFatalError(msg, locator.getLineNumber(), locator.getColumnNumber());
        }
        else {
            reportFatalError(msg, -1, -1);
        }
    }

    /**
     * Overrides {@link RDFParserBase#reportFatalError(Exception)}, adding line-
     * and column number information to the error.
     */
    @Override
    protected void reportFatalError(Exception e)
            throws RDFParseException
    {
        Locator locator = saxFilter.getLocator();
        if (locator != null) {
            reportFatalError(e, locator.getLineNumber(), locator.getColumnNumber());
        }
        else {
            reportFatalError(e, -1, -1);
        }
    }

    /*-----------------------------------------------*
     * Inner classes NodeElement and PropertyElement *
     *-----------------------------------------------*/

    static class NodeElement {

        private Resource resource;

        private boolean isVolatile = false;;

        private int liCounter = 1;

        public NodeElement(Resource resource) {
            this.resource = resource;
        }

        public Resource getResource() {
            return resource;
        }

        public void setIsVolatile(boolean isVolatile) {
            this.isVolatile = isVolatile;
        }

        public boolean isVolatile() {
            return isVolatile;
        }

        public int getNextLiCounter() {
            return liCounter++;
        }
    }

    static class PropertyElement {

        /** The property URI. */
        private URI uri;

        /** An optional reification identifier. */
        private URI reificationURI;

        /** An optional datatype. */
        private URI datatype;

        /**
         * Flag indicating whether this PropertyElement has an attribute <tt>rdf:parseType="Collection"</tt>.
         */
        private boolean parseCollection = false;

        /**
         * The resource that was used to append the last part of an rdf:List.
         */
        private Resource lastListResource;

        public PropertyElement(URI uri) {
            this.uri = uri;
        }

        public URI getURI() {
            return uri;
        }

        public boolean isReified() {
            return reificationURI != null;
        }

        public void setReificationURI(URI reifURI) {
            this.reificationURI = reifURI;
        }

        public URI getReificationURI() {
            return reificationURI;
        }

        public void setDatatype(URI datatype) {
            this.datatype = datatype;
        }

        public URI getDatatype() {
            return datatype;
        }

        public boolean parseCollection() {
            return parseCollection;
        }

        public void setParseCollection(boolean parseCollection) {
            this.parseCollection = parseCollection;
        }

        public Resource getLastListResource() {
            return lastListResource;
        }

        public void setLastListResource(Resource resource) {
            lastListResource = resource;
        }
    }

    /**
     * Implementation of SAX ErrorHandler.warning
     */
    @Override
    public void warning(SAXParseException exception)
            throws SAXException
    {
        this.reportWarning(exception.getMessage());
    }

    /**
     * Implementation of SAX ErrorHandler.error
     */
    @Override
    public void error(SAXParseException exception)
            throws SAXException
    {
        try {
            this.reportError(exception.getMessage(), XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS);
        } catch (RDFParseException rdfpe) {
            throw new SAXException(rdfpe);
        }
    }

    /**
     * Implementation of SAX ErrorHandler.fatalError
     */
    @Override
    public void fatalError(SAXParseException exception)
            throws SAXException
    {
        try {
            this.reportFatalError(exception.getMessage());
        } catch (RDFParseException rdfpe) {
            throw new SAXException(rdfpe);
        }
    }
}
