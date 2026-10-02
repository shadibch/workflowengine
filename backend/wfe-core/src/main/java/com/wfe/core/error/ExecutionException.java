package com.wfe.core.error;

/**
 * Execution reached a state the model cannot resolve — a gateway with no
 * matching outgoing flow and no default, a joined parallel gateway whose
 * contributing flows cannot all be satisfied, a call activity pointing at a
 * definition that was never published.
 *
 * <p>A design-time mistake, so the engine raises an incident against the instance
 * rather than crashing: the instance parks in {@code FAILED}, the failing element
 * is named in the error, and an operator can fix the definition and re-run.
 * Silently picking a flow would hide the defect until it produced wrong business
 * outcomes.
 */
public class ExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final long instanceId;

    public ExecutionException(long instanceId, String nodeId, String message) {
        super(message);
        this.instanceId = instanceId;
        this.nodeId = nodeId;
    }

    public ExecutionException(long instanceId, String nodeId, String message, Throwable cause) {
        super(message, cause);
        this.instanceId = instanceId;
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }

    public long instanceId() {
        return instanceId;
    }

    /** The model cannot be executed as written. */
    public static ExecutionException noOutgoingFlow(long instanceId, String nodeId) {
        return new ExecutionException(instanceId, nodeId,
                "No outgoing sequence flow matched and the node has no default flow");
    }

    /** An event waited for can never arrive under the current model. */
    public static ExecutionException unresolvableEvent(long instanceId, String nodeId, String detail) {
        return new ExecutionException(instanceId, nodeId, "Unresolvable event: " + detail);
    }
}
