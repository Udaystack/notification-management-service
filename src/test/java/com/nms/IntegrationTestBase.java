package com.nms;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: boots the full application against a shared PostgreSQL 16
 * Testcontainer. The delivery worker is disabled by default so tests control processing.
 */
@SpringBootTest(properties = "nms.worker.enabled=false")
@Import(IntegrationTestBase.Containers.class)
public abstract class IntegrationTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16");
        }
    }
}
