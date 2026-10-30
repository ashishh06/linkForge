package com.linkforge.security;

import com.linkforge.config.properties.LinkforgeProperties;
import com.linkforge.entity.Role;
import com.linkforge.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TestProperties.defaults());
    }

    @Test
    void issuesTokenWithExpectedClaimsAndLifetime() {
        IssuedToken issued = jwtService.issue(42L, "user@example.com", Role.ADMIN);

        assertThat(issued.value()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());

        Jwt jwt = jwtService.parse(issued.value());
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("user@example.com");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("linkforge");
    }

    @Test
    void rejectsTamperedPayload() {
        String token = jwtService.issue(1L, "user@example.com", Role.USER).value();
        String[] parts = token.split("\\.");
        char first = parts[1].charAt(0);
        parts[1] = (first == 'a' ? 'b' : 'a') + parts[1].substring(1);
        String tampered = String.join(".", parts);

        assertThatThrownBy(() -> jwtService.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        LinkforgeProperties other = new LinkforgeProperties(
                "http://localhost:8080",
                new LinkforgeProperties.Link(7, 2048, 5, List.of()),
                new LinkforgeProperties.Jwt(
                        "linkforge",
                        "another-secret-that-is-also-32-bytes!!",
                        Duration.ofMinutes(15)));

        String foreignToken = new JwtService(other).issue(1L, "user@example.com", Role.USER).value();

        assertThatThrownBy(() -> jwtService.parse(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokensFromAnotherIssuer() {
        LinkforgeProperties other = new LinkforgeProperties(
                "http://localhost:8080",
                new LinkforgeProperties.Link(7, 2048, 5, List.of()),
                new LinkforgeProperties.Jwt(
                        "another-issuer",
                        TestProperties.JWT_SECRET,
                        Duration.ofMinutes(15)));

        String foreignToken = new JwtService(other).issue(1L, "user@example.com", Role.USER).value();

        assertThatThrownBy(() -> jwtService.parse(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsConfiguredSecretShorterThanHs256Requires() {
        LinkforgeProperties weak = new LinkforgeProperties(
                "http://localhost:8080",
                new LinkforgeProperties.Link(7, 2048, 5, List.of()),
                new LinkforgeProperties.Jwt("linkforge", "too-short", Duration.ofMinutes(15)));

        assertThatThrownBy(() -> new JwtService(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
