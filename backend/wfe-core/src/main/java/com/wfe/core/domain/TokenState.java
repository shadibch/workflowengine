package com.wfe.core.domain;

/**
 * Where a token currently sits, which determines whether the engine may move it.
 *
 * <p>{@code BLOCKED} means "this node is waiting for other tokens to arrive"
 * (a gateway join, or an activity whose completion is still pending).
 * {@code WAITING} means "this node consumed its token and is parked on an
 * external trigger" (timer, message, signal, human action).
 */
public enum TokenState {

    /** Ready to be evaluated and advanced. */
    ACTIVE,

    /** Parked on an external trigger; released by a matching event. */
    WAITING,

    /** Waiting for sibling tokens at a join. */
    BLOCKED,

    /** Held by an administrative suspend; resumed on demand. */
    SUSPENDED;

    public boolean isLive() {
        return this != SUSPENDED;
    }
}
