package com.wfe.persistence.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Persistence wiring.
 *
 * <p>Pinned explicitly rather than relying on the auto-configuration package
 * scan: {@code WfeApplication} lives in {@code com.wfe.app}, which is not a
 * parent of {@code com.wfe.persistence}, so without these the entities and
 * repositories in this module would not be found.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EntityScan(basePackages = "com.wfe.persistence")
@EnableJpaRepositories(basePackages = "com.wfe.persistence")
public class PersistenceConfig {
}
