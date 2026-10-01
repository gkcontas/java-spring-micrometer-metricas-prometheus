package com.gkcontas.metrics.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One container for the whole suite, started once and never stopped.
 *
 * <p>Deliberately <em>not</em> {@code @Testcontainers} with {@code @Container}: that
 * extension stops the container when the test class finishes, while Spring caches the
 * application context and hands it to the next class. The second class then gets a live
 * context pointing at a dead database, and the failure reads as "connection refused" with
 * nothing in the test that explains it.
 *
 * <p>A static field started in a static initialiser lives for the whole JVM. Ryuk removes
 * it when the JVM exits, so nothing leaks.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("metrics")
            .withUsername("metrics")
            .withPassword("metrics");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The simulated latency is the point in production and dead weight here: it would
        // add seconds per test to measure a sleep nobody is asserting on.
        registry.add("app.payment.simulate-latency", () -> false);
    }

    @Autowired
    protected MockMvc mockMvc;
}
