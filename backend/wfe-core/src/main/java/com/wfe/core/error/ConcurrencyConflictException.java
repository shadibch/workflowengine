package com.wfe.core.error;

/**
 * Two workers tried to advance the same token.
 *
 * <p>Expected under normal operation, not an error condition: an inbound message
 * and a timer can both fire for the same activity. The losing transaction is
 * rolled back and its job is retried or discarded, which is why this extends
 * {@link RuntimeException} and is handled by the job dispatcher rather than
 * surfaced to a user.
 *
 * <p>Backed by the {@code lock_version} columns and the partial unique index on
 * {@code wf_token}: the database, not application code, is what guarantees a
 * gateway join releases exactly once.
 */
public class ConcurrencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long instanceId;
    private final long tokenId;

    public ConcurrencyConflictException(long instanceId, long tokenId, String message) {
        super("Concurrent execution on instance " + instanceId + ", token " + tokenId + ": " + message);
        this.instanceId = instanceId;
        this.tokenId = tokenId;
    }

    public long instanceId() {
        return instanceId;
    }

    public long tokenId() {
        return tokenId;
    }
}
