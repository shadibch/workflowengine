package com.wfe.core.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The BPMN 2.0 flow-node and event types this engine can execute.
 *
 * <p>Names match the {@code xsi:type} / element names used in BPMN 2.0 XML so the
 * compiled graph can be round-tripped and diffed against the source document
 * without a translation table.
 *
 * <p>Deliberately excluded from v1 (reachable, but not implemented):
 * {@code AD_HOC_SUB_PROCESS}, {@code CHOREOGRAPHY}. Compensation is modelled as
 * an error-boundary pattern rather than a runtime stack in v1.
 */
public enum NodeType {

    // -- Activities ---------------------------------------------------------
    TASK("task"),
    USER_TASK("userTask"),
    SERVICE_TASK("serviceTask"),
    SCRIPT_TASK("scriptTask"),
    MANUAL_TASK("manualTask"),
    BUSINESS_RULE_TASK("businessRuleTask"),
    SEND_TASK("sendTask"),
    RECEIVE_TASK("receiveTask"),
    SUB_PROCESS("subProcess"),
    TRANSACTION("transaction"),
    CALL_ACTIVITY("callActivity"),

    // -- Gateways -----------------------------------------------------------
    EXCLUSIVE_GATEWAY("exclusiveGateway"),
    PARALLEL_GATEWAY("parallelGateway"),
    INCLUSIVE_GATEWAY("inclusiveGateway"),
    COMPLEX_GATEWAY("complexGateway"),
    EVENT_BASED_GATEWAY("eventBasedGateway"),

    // -- Events -------------------------------------------------------------
    START_EVENT("startEvent"),
    END_EVENT("endEvent"),
    INTERMEDIATE_THROW_EVENT("intermediateThrowEvent"),
    INTERMEDIATE_CATCH_EVENT("intermediateCatchEvent"),
    BOUNDARY_EVENT("boundaryEvent");

    private final String bpmnName;

    NodeType(String bpmnName) {
        this.bpmnName = bpmnName;
    }

    /** The BPMN 2.0 element name. Service calls are {@code serviceTask} with a {@code wfe:kind}. */
    public String bpmnName() {
        return bpmnName;
    }

    public boolean isEvent() {
        return name().endsWith("_EVENT") || this == EVENT_BASED_GATEWAY;
    }

    public boolean isGateway() {
        return name().endsWith("_GATEWAY");
    }

    public boolean isActivity() {
        return this == TASK
                || this == USER_TASK
                || this == SERVICE_TASK
                || this == SCRIPT_TASK
                || this == MANUAL_TASK
                || this == BUSINESS_RULE_TASK
                || this == SEND_TASK
                || this == RECEIVE_TASK
                || this == SUB_PROCESS
                || this == TRANSACTION
                || this == CALL_ACTIVITY;
    }

    /**
     * Resolves a BPMN element name to a type.
     *
     * <p>Accepts both the plain element name and the {@code bpmn2:} / {@code wfe:}
     * qualified forms so callers can pass whatever the XML parser handed them.
     */
    public static Optional<NodeType> fromBpmnName(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        String normalised = normalise(rawName);
        for (NodeType type : values()) {
            if (type.bpmnName.equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * Strips a namespace prefix and lower-cases with {@code _} separators, so
     * {@code bpmn2:userTask}, {@code userTask} and {@code USER_TASK} all map to
     * the same constant. BPMN's mixed-case names make the underscore form the
     * only safe canonical representation.
     */
    public static String normalise(String rawName) {
        int colon = rawName.indexOf(':');
        String local = colon >= 0 ? rawName.substring(colon + 1) : rawName;
        StringBuilder sb = new StringBuilder(local.length());
        for (char c : local.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (!sb.isEmpty()) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
