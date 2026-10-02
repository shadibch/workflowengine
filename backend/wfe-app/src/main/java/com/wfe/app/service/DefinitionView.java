package com.wfe.app.service;

import com.wfe.persistence.design.WfDefinitionEntity;
import com.wfe.persistence.design.WfVersionEntity;

import java.time.Instant;

/**
 * What the SPA needs to render the definition list and the definition header.
 *
 * <p>Deliberately not the entity: {@code draftRevision} is exposed because the
 * client must send it back, but {@code deletedAt} and the audit columns are not,
 * so the API surface cannot drift into persistence details.
 */
public record DefinitionView(Long id,
                             String key,
                             String name,
                             String description,
                             String category,
                             WfDefinitionEntity.Status status,
                             long draftRevision,
                             Integer latestPublishedVersion,
                             Instant createdAt,
                             Instant updatedAt) {

    public static DefinitionView of(WfDefinitionEntity definition, Integer latestPublishedVersion) {
        return new DefinitionView(
                definition.getId(),
                definition.getKey(),
                definition.getName(),
                definition.getDescription(),
                definition.getCategory(),
                definition.getStatus(),
                definition.getDraftRevision() == null ? 0L : definition.getDraftRevision(),
                latestPublishedVersion,
                definition.getCreatedAt(),
                definition.getUpdatedAt());
    }
}
