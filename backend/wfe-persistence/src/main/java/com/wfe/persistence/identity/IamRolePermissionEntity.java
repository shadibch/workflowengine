package com.wfe.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * Links a role to an API permission.
 *
 * <p>Roles are what BPMN names in {@code candidateGroups}; permissions are what
 * the REST layer checks. Keeping them separate means a workflow can offer
 * "ROLE_REVIEWER" as a work queue without that role also granting the ability to
 * publish workflow definitions.
 */
@Entity
@Table(name = "iam_role_permission")
public class IamRolePermissionEntity {

    @jakarta.persistence.Embeddable
    public record Id(Long roleId, Long permissionId) implements Serializable {

        public Id {
            Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(permissionId, "permissionId");
        }
    }

    @EmbeddedId
    private Id id;

    protected IamRolePermissionEntity() {
        // for JPA
    }

    public Id getId() {
        return id;
    }

    public void setId(Id id) {
        this.id = id;
    }
}
