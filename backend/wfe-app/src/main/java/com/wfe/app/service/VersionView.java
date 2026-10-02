package com.wfe.app.service;

import com.wfe.persistence.design.WfVersionEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One row of a definition's version history.
 *
 * <p>No {@code bpmnXml}: the history list is rendered for dozens of versions and
 * nobody needs megabytes of XML to do that. The document itself comes from
 * {@link VersionDetail}, fetched only when a version is opened.
 */
public record VersionView(int versionNo,
                          WfVersionEntity.Status status,
                          String checksum,
                          String notes,
                          String shortChecksum,
                          List<Map<String, Object>> validation,
                          Instant createdAt,
                          Instant publishedAt) {

    /** First 12 hex characters: enough to recognise a version in a list, too short to be mistaken for a real digest. */
    static final int SHORT_CHECKSUM_LENGTH = 12;

    public static VersionView of(WfVersionEntity version) {
        String checksum = version.getChecksum();
        return new VersionView(
                version.getVersionNo(),
                version.getStatus(),
                checksum,
                version.getNotes(),
                checksum == null ? null : checksum.substring(0, Math.min(SHORT_CHECKSUM_LENGTH, checksum.length())),
                version.getValidation(),
                version.getCreatedAt(),
                version.getPublishedAt());
    }
}
