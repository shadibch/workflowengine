package com.wfe.app.service;

import com.wfe.persistence.design.WfDefinitionEntity;
import com.wfe.persistence.design.WfVersionEntity;

import java.time.Instant;

/**
 * The mutable document a designer is editing.
 *
 * <p>{@code revision} is the token the client must echo back on the next save; it
 * is the same value as the definition's {@code draft_revision}.
 */
public record DraftView(String key,
                        long revision,
                        int versionNo,
                        String bpmnXml,
                        String checksum,
                        Instant updatedAt) {

    public static DraftView of(WfDefinitionEntity definition, WfVersionEntity draft) {
        return new DraftView(
                definition.getKey(),
                definition.getDraftRevision() == null ? 0L : definition.getDraftRevision(),
                draft.getVersionNo(),
                draft.getBpmnXml(),
                draft.getChecksum(),
                draft.getUpdatedAt());
    }
}
