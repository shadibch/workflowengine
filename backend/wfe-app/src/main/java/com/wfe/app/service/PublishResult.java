package com.wfe.app.service;

/**
 * Outcome of a publish request.
 *
 * <p>{@code created} is false when the draft was already published: republishing an
 * unchanged design returns 200 with the version that was already created rather
 * than inventing {@code v5} that says the same thing. A caller who needs to know
 * whether a new version exists uses this flag; it is not an error either way.
 */
public record PublishResult(VersionView version, boolean created, Integer draftVersionNo) {
}
