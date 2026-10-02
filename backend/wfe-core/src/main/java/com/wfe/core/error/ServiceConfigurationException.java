package com.wfe.core.error;

/**
 * An outbound call could not be made at all — no such connection, an adapter for
 * the protocol is not registered, a template failed to render.
 *
 * <p>Distinct from a call that was made and failed, which is a normal
 * {@link com.wfe.core.port.ServiceInvoker.Outcome} rather than an exception: a
 * 500 from a remote system is data the workflow routes on, not a bug.
 */
public class ServiceConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String nodeId;

    public ServiceConfigurationException(String nodeId, String message) {
        super(message);
        this.nodeId = nodeId;
    }

    public ServiceConfigurationException(String nodeId, String message, Throwable cause) {
        super(message, cause);
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }
}
