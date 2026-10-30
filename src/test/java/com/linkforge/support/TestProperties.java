package com.linkforge.support;

import com.linkforge.config.properties.LinkforgeProperties;

import java.time.Duration;
import java.util.List;

/**
 * Builds the configuration record without starting Spring, for unit tests.
 */
public final class TestProperties {

    public static final String JWT_SECRET = "test-secret-with-at-least-32-bytes!!";

    private TestProperties() {
    }

    public static LinkforgeProperties defaults() {
        return create(7, 5, List.of(
                "api", "actuator", "admin", "login", "register",
                "swagger-ui", "v3", "error", "health", "favicon.ico"));
    }

    public static LinkforgeProperties create(int codeLength, int maxCollisionRetries, List<String> reservedAliases) {
        return new LinkforgeProperties(
                "http://localhost:8080",
                new LinkforgeProperties.Link(codeLength, 2048, maxCollisionRetries, reservedAliases),
                new LinkforgeProperties.Jwt("linkforge", JWT_SECRET, Duration.ofMinutes(15)));
    }
}
