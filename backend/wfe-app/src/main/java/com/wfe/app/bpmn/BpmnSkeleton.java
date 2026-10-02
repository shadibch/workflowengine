package com.wfe.app.bpmn;

/**
 * The document a brand-new definition starts from.
 *
 * <p>A definition with no XML is not a useful empty state: the designer would open
 * the canvas and have to add a start event, an end event and the flow between them
 * before anything could be saved. So creation writes a minimal but <em>complete</em>
 * process — start, end, one sequence flow, plus diagram interchange so the shapes
 * have positions and open visible instead of on a blank canvas.
 *
 * <p>Ids are derived from the business key, sanitised to XML {@code NCName}, because
 * BPMN ids must be unique within the document and cannot contain spaces or start
 * with a digit.
 */
public final class BpmnSkeleton {

    private static final String TARGET_NAMESPACE = "https://wfe.dev/bpmn";

    private BpmnSkeleton() {
    }

    /**
     * @param key business key of the new definition, used to derive element ids
     * @param name display name of the process element
     */
    public static String minimalProcess(String key, String name) {
        String suffix = ncName(key);
        String processId = "Process_" + suffix;
        String startId = "StartEvent_1";
        String endId = "EndEvent_1";
        String flowId = "Flow_1";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
                xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" \
                xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" \
                xmlns:di="http://www.omg.org/spec/DD/20100524/DI" \
                xmlns:wfe="https://wfe.dev/schema/bpmn/wfe" \
                id="Definitions_%1$s" targetNamespace="%2$s">
                  <bpmn:process id="%3$s" name="%4$s" isExecutable="true">
                    <bpmn:startEvent id="%5$s" name="Start">
                      <bpmn:outgoing>%7$s</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:endEvent id="%6$s" name="End">
                      <bpmn:incoming>%7$s</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="%7$s" sourceRef="%5$s" targetRef="%6$s"/>
                  </bpmn:process>
                  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
                    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="%3$s">
                      <bpmndi:BPMNShape id="%5$s_di" bpmnElement="%5$s">
                        <dc:Bounds x="180" y="200" width="36" height="36"/>
                      </bpmndi:BPMNShape>
                      <bpmndi:BPMNShape id="%6$s_di" bpmnElement="%6$s">
                        <dc:Bounds x="440" y="202" width="36" height="36"/>
                      </bpmndi:BPMNShape>
                      <bpmndi:BPMNEdge id="%7$s_di" bpmnElement="%7$s">
                        <di:waypoint x="216" y="218"/>
                        <di:waypoint x="440" y="220"/>
                      </bpmndi:BPMNEdge>
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </bpmn:definitions>
                """.formatted(escape(suffix), TARGET_NAMESPACE, processId, escape(name), startId, endId, flowId);
    }

    /** Reduces arbitrary text to a legal XML {@code NCName} fragment. */
    static String ncName(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            boolean legal = Character.isLetterOrDigit(c) || c == '_' || c == '-';
            result.append(legal ? c : '_');
        }
        String candidate = result.toString();
        // An NCName may not start with a digit, a dot or a hyphen.
        if (!candidate.isEmpty() && (Character.isDigit(candidate.charAt(0)) || candidate.charAt(0) == '.')) {
            candidate = "_" + candidate;
        }
        return candidate.isEmpty() ? "process" : candidate;
    }

    /** Escapes text for an XML attribute value. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (Character.isISOControl(c)) {
                        // XML 1.0 forbids most control characters outright; replacing
                        // them keeps a pasted name from making the document invalid.
                        out.append(' ');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
