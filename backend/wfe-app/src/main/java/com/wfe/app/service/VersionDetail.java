package com.wfe.app.service;

import com.wfe.persistence.design.WfVersionEntity;

import java.time.Instant;

/**
 * A version together with its document.
 *
 * <p>Used when the designer opens a published version read-only: it needs the exact
 * XML that instances are running, not the latest draft.
 */
public record VersionDetail(VersionView version, String bpmnXml) {

    public static VersionDetail of(WfVersionEntity entity) {
        return new VersionDetail(VersionView.of(entity), entity.getBpmnXml());
    }
}
