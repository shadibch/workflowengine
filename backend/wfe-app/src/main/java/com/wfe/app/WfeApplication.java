package com.wfe.app;

import com.wfe.persistence.config.PersistenceConfig;
import com.wfe.security.SecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Application entry point.
 *
 * <p>Component scanning starts at {@code com.wfe.app}, so the modules it composes
 * are not picked up automatically — each declares its own {@code @Configuration}
 * with explicit base packages ({@link com.wfe.persistence.config.PersistenceConfig}
 * and friends), wired in here. That keeps the dependency direction visible in
 * code instead of implied by package naming, and stops a stray {@code @Service}
 * in a library module from being auto-registered.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.wfe")
@Import({PersistenceConfig.class, SecurityConfig.class})
public class WfeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WfeApplication.class, args);
    }
}
