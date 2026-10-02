package com.wfe.core.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The request cannot be applied because it conflicts with current state.
 *
 * <p>Covers the three cases the definition API can hit, which are deliberately
 * one exception rather than three: the business key is already taken (409), the
 * caller's draft revision is stale because someone else saved first (409), and
 * the caller tried to publish XML that is byte-identical to an existing version
 * (which is a no-op, not an error, so it is signalled as a conflict detail by the
 * caller rather than thrown).
 *
 * <p>Conflict is the right status rather than 400: the request was well-formed,
 * it just does not fit the state the system is in now.
 */
public class ResourceConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, Object> properties;

    public ResourceConflictException(String code, String detail) {
        this(code, detail, Map.of());
    }

    public ResourceConflictException(String code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    /**
     * The business key is in use. The suggested key lets the UI offer an
     * alternative instead of making the user guess.
     */
    public static ResourceConflictException duplicateKey(String key, String suggestion) {
        return new ResourceConflictException("definition-key-taken",
                "Definition key '%s' already exists".formatted(key),
                Map.of("key", key, "suggestion", suggestion));
    }

    /**
     * Optimistic lock failure on the mutable draft row.
     *
     * @param expectedRevision revision the client based its edit on
     * @param actualRevision   revision currently stored, which the client can rebase onto
     */
    public static ResourceConflictException staleDraft(String key, long expectedRevision, long actualRevision) {
        return new ResourceConflictException("draft-stale",
                "The draft was modified since revision " + expectedRevision + "; reload and re-apply your changes",
                Map.of("key", key, "expectedRevision", expectedRevision, "currentRevision", actualRevision));
    }

    public String code() {
        return code;
    }

    public Map<String, Object> problemProperties() {
        return properties;
    }

    /** Convenience for building an RFC 9457 body extension in one place. */
    public LinkedHashMap<String, Object> problemBody() {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.putAll(properties);
        return body;
    }
}
