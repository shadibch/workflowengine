package com.wfe.core.domain;

/**
 * Process instance lifecycle.
 *
 * <p>{@code EXTERNALLY_TERMINATED} is distinct from {@code CANCELLED} because a
 * terminate end event inside the model is a designed outcome while a
 * cancellation is an operator action, and the dashboard reports them apart.
 */
public enum InstanceStatus {

    RUNNING,
    SUSPENDED,
    COMPLETED,
    CANCELLED,
    FAILED,
    EXTERNALLY_TERMINATED;

    public boolean isActive() {
        return this == RUNNING || this == SUSPENDED;
    }

    public boolean isTerminated() {
        return !isActive();
    }
}
