package com.linkforge.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 *
 * <p>Containers are <strong>singletons</strong>: started once in a static
 * initialiser and shared by every test class in the JVM, instead of being
 * restarted per class (which is the slowest part of a Testcontainers suite).
 * Ryuk removes them when the JVM exits.
 *
 * <p>Postgres is a plain {@link GenericContainer} rather than
 * {@code PostgreSQLContainer} so the test setup does not depend on the
 * Testcontainers module layout; readiness is asserted from the server log.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withEnv("POSTGRES_DB", "linkforge")
            .withEnv("POSTGRES_USER", "linkforge")
            .withEnv("POSTGRES_PASSWORD", "linkforge")
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/linkforge"
                .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)));
        registry.add("spring.datasource.username", () -> "linkforge");
        registry.add("spring.datasource.password", () -> "linkforge");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
