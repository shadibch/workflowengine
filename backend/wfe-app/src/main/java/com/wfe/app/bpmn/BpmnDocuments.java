package com.wfe.app.bpmn;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Reading, canonicalising and checksumming designer XML.
 *
 * <h2>Why parsing is locked down</h2>
 * A BPMN payload is untrusted input: anyone with {@code definition:write} can POST
 * arbitrary XML and the API parses it. The default JAXP configuration resolves
 * external entities and DTDs, which turns "save a draft" into a server-side file
 * reader ({@code XXE}) and a denial-of-service amplifier (billion laughs). Every
 * parser here is built with external access switched off, and DTDs rejected
 * outright — a BPMN diagram has no legitimate need for either.
 *
 * <h2>Why canonicalisation exists</h2>
 * {@code checksum} decides whether a publish is a new version or a no-op. Comparing
 * raw bytes would make re-serialising the same diagram with different indentation
 * or attribute order look like a change, so the checksum is taken over a
 * canonical form: same DOM, whitespace-only text nodes removed, no XML declaration,
 * attributes in document order.
 */
public final class BpmnDocuments {

    private static final String FEATURE_DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_EXTERNAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String FEATURE_LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private BpmnDocuments() {
    }

    /**
     * Reports nothing to the console and fails the parse instead.
     *
     * <p>Warnings are swallowed on purpose: only a fatal error matters here, and a
     * recoverable warning from a foreign namespace must not fail a legitimate
     * diagram.
     */
    private static final ErrorHandler SILENT_ERROR_HANDLER = new ErrorHandler() {

        @Override
        public void warning(SAXParseException exception) {
            // ignored
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    /** Raised when the payload is not well-formed XML. */
    public static class MalformedXmlException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MalformedXmlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Parses a payload into a DOM with external resolution disabled.
     *
     * @throws MalformedXmlException if the document is not well-formed or tries to
     *                               use a construct that is not allowed
     */
    public static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(FEATURE_DISALLOW_DOCTYPE, true);
            factory.setFeature(FEATURE_EXTERNAL_ENTITIES, false);
            factory.setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            // A parser must never resolve anything off the document itself; an
            // empty handler plus secure features means a bad document is an error,
            // not a fetch.
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            // The default handler prints "[Fatal Error] ..." to stderr and then
            // throws. Autosave sends deliberately half-finished XML all day, so
            // that output would flood the logs with lines that look like incidents.
            // Throwing without printing leaves the decision to the caller.
            builder.setErrorHandler(SILENT_ERROR_HANDLER);
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException | IOException e) {
            throw new MalformedXmlException("Payload is not well-formed XML: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            // Reaching here means the JVM rejected a hardening flag, which is a
            // platform problem rather than a caller problem: fail loudly instead
            // of parsing unsafely.
            throw new IllegalStateException("XML parser could not be configured securely", e);
        }
    }

    /**
     * The document in a form that compares equal for equal designs.
     *
     * <p>Whitespace-only text nodes are dropped because they are layout, not
     * content; two exports of the same diagram that differ only in indentation
     * canonicalise identically.
     */
    public static String canonicalize(String xml) {
        Document document = parse(xml);
        stripInsignificantWhitespace(document.getDocumentElement());
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException("Could not canonicalise the document", e);
        }
    }

    /**
     * SHA-256 of the canonical form, lowercase hex.
     *
     * <p>Never throws: a draft may legitimately be mid-edit and not yet
     * well-formed, and autosave must still be able to store a revision. Malformed
     * input falls back to hashing the trimmed text, which is still a stable
     * identity for "the same broken document", and validation reports the
     * malformation separately.
     */
    public static String checksum(String xml) {
        String basis;
        try {
            basis = canonicalize(xml);
        } catch (MalformedXmlException e) {
            basis = xml.strip();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(basis.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** Depth-first removal of whitespace-only text nodes. */
    private static void stripInsignificantWhitespace(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                if (child.getTextContent().isBlank()) {
                    node.removeChild(child);
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripInsignificantWhitespace(child);
            }
        }
    }
}
