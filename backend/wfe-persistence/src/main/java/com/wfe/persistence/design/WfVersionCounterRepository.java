package com.wfe.persistence.design;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Version-number allocation. Rows are created lazily with the definition, inside
 * the same transaction, so publishing never has to insert a counter row on the
 * hot path.
 */
public interface WfVersionCounterRepository extends JpaRepository<WfVersionCounterEntity, Long> {
}
