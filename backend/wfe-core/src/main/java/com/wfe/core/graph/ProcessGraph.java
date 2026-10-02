package com.wfe.core.graph;

import com.wfe.core.domain.NodeType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The compiled, execution-ready form of one published workflow version.
 *
 * <p>Built once at publish time from the stored BPMN XML and then never mutated,
 * so every running instance of a version shares one immutable structure. This is
 * the only thing the engine traverses at runtime — it never re-parses XML, which
 * is why executing is fast and why a running instance is unaffected by edits to
 * the draft.
 *
 * <p>The XML remains the source of truth for export. This graph is derived and
 * disposable: deleting {@code wf_version.graph} and recompiling must produce an
 * identical structure.
 *
 * @param nodeById     every executable node, keyed by BPMN element id
 * @param flowById     every sequence flow, keyed by id
 * @param startNodeIds start events; more than one is legal (a multi-start design)
 * @param endNodeIds   end events, used by terminate-all semantics
 * @param messages     message, signal and error definitions by id, for event matching
 * @param timers       timer definitions by id, for calendar resolution
 */
public record ProcessGraph(
        String processId,
        String versionNo,
        Map<String, FlowNode> nodeById,
        Map<String, SequenceFlow> flowById,
        List<String> startNodeIds,
        List<String> endNodeIds,
        Map<String, EventDefinitionRef> messages,
        Map<String, TimerDefinition> timers) {

    public ProcessGraph {
        nodeById = Map.copyOf(nodeById);
        flowById = Map.copyOf(flowById);
        startNodeIds = List.copyOf(startNodeIds);
        endNodeIds = List.copyOf(endNodeIds);
        messages = messages == null ? Map.of() : Map.copyOf(messages);
        timers = timers == null ? Map.of() : Map.copyOf(timers);
    }

    public Optional<FlowNode> node(String nodeId) {
        return Optional.ofNullable(nodeById.get(nodeId));
    }

    public FlowNode requireNode(String nodeId) {
        FlowNode node = nodeById.get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Node " + nodeId + " is not part of graph " + processId);
        }
        return node;
    }

    /**
     * A flow node.
     *
     * @param incoming        flows arriving here, in document order. The order
     *                        matters for exclusive gateways: the first condition
     *                        that evaluates true wins, so the diagram's authoring
     *                        order is the evaluation order.
     * @param outgoing        flows leaving here, likewise in document order
     * @param defaultFlowId   the flow marked {@code default="true"}, at most one
     * @param eventDefinition for catch/throw nodes and boundary events
     * @param boundaryEvents  events attached to this activity; an activity with
     *                        boundaries runs them concurrently rather than
     *                        consuming the token
     * @param multiInstance   multi-instance configuration, or empty
     */
    public record FlowNode(String id, String name, NodeType type,
                           List<String> incoming, List<String> outgoing, String defaultFlowId,
                           EventDefinitionRef eventDefinition, List<BoundaryEventRef> boundaryEvents,
                           Optional<MultiInstanceConfig> multiInstance,
                           Map<String, String> attributes) {

        public FlowNode {
            incoming = incoming == null ? List.of() : List.copyOf(incoming);
            outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
            boundaryEvents = boundaryEvents == null ? List.of() : List.copyOf(boundaryEvents);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public boolean isExclusiveGateway() {
            return type == NodeType.EXCLUSIVE_GATEWAY;
        }

        public boolean isParallelGateway() {
            return type == NodeType.PARALLEL_GATEWAY;
        }

        public boolean isInclusiveGateway() {
            return type == NodeType.INCLUSIVE_GATEWAY;
        }

        /** A gateway that must wait for all its incoming flows before firing. */
        public boolean isJoin() {
            return isParallelGateway() || isComplexGateway();
        }

        public boolean isComplexGateway() {
            return type == NodeType.COMPLEX_GATEWAY;
        }

        /** A gateway that produces one token per selected outgoing flow. */
        public boolean isSplit() {
            return isExclusiveGateway() || isParallelGateway()
                    || isInclusiveGateway() || isComplexGateway();
        }

        /**
         * Whether the node consumes the token as it arrives (rather than
         * forwarding it straight through). Events and task-like nodes do; a
         * gateway with no boundary behaviour does not.
         */
        public boolean consumesToken() {
            return !type.isGateway();
        }
    }

    /**
     * A sequence flow.
     *
     * @param condition the {@code conditionExpression} source, or empty. A flow
     *                  with a condition is only taken when it evaluates true; a
     *                  flow without one is unconditional.
     */
    public record SequenceFlow(String id, String name, String sourceRef, String targetRef,
                               String condition, boolean isDefault) {

        public boolean hasCondition() {
            return condition != null && !condition.isBlank();
        }
    }

    /**
     * A boundary event's definition, plus the flag that decides whether the
     * activity's token survives it.
     *
     * @param cancelActivity {@code true} for an interrupting boundary event
     *                       (the default in BPMN): the activity is cancelled and
     *                       its token moves to the boundary event.
     */
    public record BoundaryEventRef(String eventNodeId, EventDefinitionRef definition,
                                   boolean cancelActivity) {
    }

    /**
     * A message, signal, error or escalation definition, used to match an
     * incoming event to a waiting token.
     *
     * @param kind      the event-definition kind
     * @param refId     the referenced {@code message}/{@code signal}/{@code error}
     *                  element id, or {@code null} for an anonymous definition
     * @param name      resolved name, for diagnostics
     * @param correlationExpression a JUEL expression evaluated against instance
     *                  variables to compute the correlation key when the event is
     *                  thrown; this is what allows a message to find the right
     *                  instance among many
     * @param payloadMapping how thrown variables are projected
     */
    public record EventDefinitionRef(Kind kind, String refId, String name,
                                     String correlationExpression,
                                     List<com.wfe.core.port.ResponseMapper.Mapping> payloadMapping) {

        public EventDefinitionRef {
            payloadMapping = payloadMapping == null ? List.of() : List.copyOf(payloadMapping);
        }

        public enum Kind {
            NONE, MESSAGE, SIGNAL, ERROR, ESCALATION, COMPENSATION, CONDITIONAL, LINK, TERMINATE
        }
    }

    /**
     * A timer event definition.
     *
     * @param timeDuration ISO-8601 duration, e.g. {@code PT30M}
     * @param timeDate     absolute trigger time
     * @param timeCycle   ISO-8601 repeating cycle, e.g. {@code R3/PT1H}
     * @param businessCalendar calendar code used to resolve working time; empty
     *                        means the tenant default
     */
    public record TimerDefinition(String timeDuration, java.time.Instant timeDate,
                                  String timeCycle, String businessCalendar) {
    }

    /**
     * Multi-instance configuration.
     *
     * @param cardinality     the element collection name, e.g. {@code items}, or
     *                        an expression/loop cardinality
     * @param cardinalityValue the resolved value when it is a literal or
     *                        expression source
     * @param collectionVariable variable holding the collection, for
     *                        {@code isSequential=false} with a variable source
     * @param loopCardinality  loop expression, e.g. {@code ${count}}
     * @param completionCondition JUEL evaluated after each instance completes;
     *                        non-empty means the activity may finish early
     * @param sequential      sequential (ordered, one token) versus parallel
     *                        (all instances started at once)
     */
    public record MultiInstanceConfig(String cardinality, String cardinalityValue,
                                      String collectionVariable, String loopCardinality,
                                      String completionCondition, boolean sequential) {

        public boolean isSequential() {
            return sequential;
        }

        public boolean hasCompletionCondition() {
            return completionCondition != null && !completionCondition.isBlank();
        }
    }
}
