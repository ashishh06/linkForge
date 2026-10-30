package com.linkforge.security;

import com.linkforge.config.properties.LinkforgeProperties;
import com.linkforge.entity.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Issues and verifies HS256 access tokens.
 *
 * <p>The signing key comes from {@code linkforge.jwt.secret} (environment variable
 * {@code JWT_SECRET} in deployment). HMAC requires at least 32 bytes, which is
 * enforced before the first token is issued rather than failing at signing time.
 *
 * <p>Only the access token exists in Phase 1. Phase 2 adds refresh tokens, rotation
 * and reuse detection; {@link #issue(Long, String, Role)} will keep its behaviour for
 * the access token half.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final LinkforgeProperties.Jwt properties;

    public JwtService(LinkforgeProperties properties) {
        this.properties = properties.jwt();

        byte[] secret = this.properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "linkforge.jwt.secret must be at least 32 UTF-8 bytes for HS256");
        }

        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key)
                .algorithm(MacAlgorithm.HS256)
                .build();

        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        nimbusDecoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer(this.properties.issuer()));
        this.decoder = nimbusDecoder;
    }

    public IssuedToken issue(Long userId, String email, Role role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", email)
                .claim("role", role.name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(token, properties.accessTokenTtl().toSeconds(), expiresAt);
    }

    /**
     * Verifies the signature, issuer and expiry.
     *
     * @throws org.springframework.security.oauth2.jwt.JwtException when the token is
     *         malformed, tampered with, expired or from another issuer
     */
    public Jwt parse(String token) {
        return decoder.decode(token);
    }
}
