package com.wfe.app.bpmn;

import com.wfe.core.error.WorkflowValidationException;
import com.wfe.core.error.WorkflowValidationException.Problem;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validation of a BPMN document.
 *
 * <h2>Scope, deliberately</h2>
 * This checks that the document is a well-formed BPMN file with a start and an end
 * and no dangling references — the mistakes a hand-edited or half-finished diagram
 * actually has. It does not check that the process is <em>semantically</em> sound
 * (every gateway has a reachable join, no unreachable nodes, variables used are
 * mapped); those need the execution graph and arrive with the compiler.
 *
 * <p>Every finding carries a BPMN element id so the canvas can place a marker on
 * the shape that caused it, and a stable {@code WFE-1xxx} code the SPA maps to a
 * localised, actionable message. The English text here is never shown raw.
 *
 * <p>Stateless, and therefore a plain singleton bean: the document is passed in per
 * call rather than held, so there is nothing to share unsafely between requests.
 */
@Component
public class BpmnStructureValidator {

    /** Problems are collected, not thrown: the designer fixes them all in one pass. */
    public List<Problem> validate(String xml) {
        List<Problem> problems = new ArrayList<>();

        Document document;
        try {
            document = BpmnDocuments.parse(xml);
        } catch (BpmnDocuments.MalformedXmlException e) {
            problems.add(Problem.error(null, "WFE-1001", "The diagram is not valid XML: " + rootMessage(e)));
            return problems;
        }

        Element root = document.getDocumentElement();
        if (!"definitions".equals(root.getLocalName())) {
            problems.add(Problem.error(null, "WFE-1002",
                    "Expected a BPMN <definitions> document but found <" + root.getNodeName() + ">"));
            return problems;
        }

        List<Element> processes = elementsByLocalName(root, "process");
        if (processes.isEmpty()) {
            problems.add(Problem.error(null, "WFE-1003", "The file contains no process"));
            return problems;
        }
        if (processes.size() > 1) {
            problems.add(Problem.error(null, "WFE-1004",
                    "The file contains " + processes.size()
                            + " processes; a definition holds exactly one so instances are unambiguous"));
        }

        Element process = processes.get(0);
        String processId = process.getAttribute("id");

        if (!process.getAttribute("isExecutable").equalsIgnoreCase("true")) {
            problems.add(Problem.warning(processId, "WFE-1005",
                    "This process is not executable, so instances cannot be started from it"));
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        collectNodeIds(process, nodeIds, problems);

        List<Element> startEvents = elementsByLocalName(process, "startEvent");
        if (startEvents.isEmpty()) {
            problems.add(Problem.error(processId, "WFE-1006", "The process has no start event"));
        }

        List<Element> endEvents = elementsByLocalName(process, "endEvent");
        if (endEvents.isEmpty()) {
            problems.add(Problem.error(processId, "WFE-1007", "The process has no end event"));
        }

        validateSequenceFlows(process, nodeIds, problems);
        validateFlowReferences(process, problems);

        return problems;
    }

    /**
     * Collects flow-node ids and reports missing or duplicated ones.
     *
     * <p>Duplicates matter more than they look: BPMN {@code id} is the join key for
     * token creation and for variable mapping, so two nodes sharing an id makes a
     * gateway join fire twice or not at all.
     */
    private void collectNodeIds(Element process, Set<String> nodeIds, List<Problem> problems) {
        NodeList all = process.getElementsByTagName("*");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            String localName = element.getLocalName();
            if (!isFlowNode(localName)) {
                continue;
            }
            String id = element.getAttribute("id");
            if (id == null || id.isBlank()) {
                problems.add(Problem.error(null, "WFE-1008",
                        "A <" + localName + "> has no id", elementPath(element)));
                continue;
            }
            if (!seen.add(id)) {
                problems.add(Problem.error(id, "WFE-1009",
                        "Duplicate element id '" + id + "'; ids must be unique within a process"));
            }
            nodeIds.add(id);
        }
    }

    /** Every flow must reference nodes that exist, or the engine dead-ends at runtime. */
    private void validateSequenceFlows(Element process, Set<String> nodeIds, List<Problem> problems) {
        for (Element flow : elementsByLocalName(process, "sequenceFlow")) {
            String id = flow.getAttribute("id");
            String source = flow.getAttribute("sourceRef");
            String target = flow.getAttribute("targetRef");
            if (source.isBlank() || target.isBlank()) {
                problems.add(Problem.error(id, "WFE-1010",
                        "A sequence flow is missing its source or target", elementPath(flow)));
                continue;
            }
            if (!nodeIds.contains(source)) {
                problems.add(Problem.error(id, "WFE-1011",
                        "Sequence flow starts at unknown element '" + source + "'", "@sourceRef"));
            }
            if (!nodeIds.contains(target)) {
                problems.add(Problem.error(id, "WFE-1012",
                        "Sequence flow ends at unknown element '" + target + "'", "@targetRef"));
            }
        }
    }

    /**
     * An {@code <incoming>} or {@code <outgoing>} child that contradicts
     * {@code sourceRef}/{@code targetRef} is a designer slip the canvas usually
     * hides, so it is worth saying out loud before publishing.
     */
    private void validateFlowReferences(Element process, List<Problem> problems) {
        for (Element flow : elementsByLocalName(process, "sequenceFlow")) {
            String flowId = flow.getAttribute("id");
            String source = flow.getAttribute("sourceRef");
            String target = flow.getAttribute("targetRef");
            for (Element reference : childElements(flow, "incoming")) {
                String referenced = reference.getTextContent().strip();
                if (!referenced.equals(source)) {
                    problems.add(Problem.warning(flowId, "WFE-1013",
                            "Flow declares incoming '" + referenced
                                    + "' but its sourceRef is '" + source + "'"));
                }
            }
            for (Element reference : childElements(flow, "outgoing")) {
                String referenced = reference.getTextContent().strip();
                if (!referenced.equals(target)) {
                    problems.add(Problem.warning(flowId, "WFE-1014",
                            "Flow declares outgoing '" + referenced
                                    + "' but its targetRef is '" + target + "'"));
                }
            }
        }
    }

    private static boolean isFlowNode(String localName) {
        return switch (localName) {
            case "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
                 "boundaryEvent", "task", "userTask", "serviceTask", "sendTask", "receiveTask",
                 "manualTask", "scriptTask", "businessRuleTask", "callActivity", "subProcess",
                 "transaction", "exclusiveGateway", "parallelGateway", "inclusiveGateway",
                 "eventBasedGateway", "complexGateway" -> true;
            default -> false;
        };
    }

    private static List<Element> elementsByLocalName(Element parent, String localName) {
        List<Element> matches = new ArrayList<>();
        NodeList all = parent.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static List<Element> childElements(Element parent, String... localNames) {
        List<Element> matches = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                for (String localName : localNames) {
                    if (localName.equals(element.getLocalName())) {
                        matches.add(element);
                        break;
                    }
                }
            }
        }
        return matches;
    }

    /** Dotted element path, for a validator message that points at the XML. */
    private static String elementPath(Element element) {
        StringBuilder path = new StringBuilder(element.getLocalName());
        Node parent = element.getParentNode();
        while (parent instanceof Element parentElement) {
            if (!"definitions".equals(parentElement.getLocalName())) {
                path.insert(0, parentElement.getLocalName() + "/");
            }
            parent = parentElement.getParentNode();
        }
        return path.toString();
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) {
            return throwable.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** Convenience for callers that treat any finding as fatal. */
    public static boolean isPublishable(List<Problem> problems) {
        return problems.stream().noneMatch(Problem::isBlocking);
    }

    /** Validates and throws if anything blocking was found, for publish. */
    public List<Problem> validateOrThrow(String xml) {
        List<Problem> problems = validate(xml);
        if (!isPublishable(problems)) {
            throw new WorkflowValidationException(problems);
        }
        return problems;
    }
}
