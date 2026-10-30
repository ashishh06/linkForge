package com.linkforge.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Typed view of the {@code linkforge.*} configuration tree.
 *
 * <p>Records are used deliberately: the configuration is immutable once bound, and
 * binding fails at startup when a value is missing or invalid instead of surfacing
 * as a {@code NullPointerException} on the first request.
 */
@Validated
@ConfigurationProperties(prefix = "linkforge")
public record LinkforgeProperties(
        @NotBlank String baseUrl,
        @NotNull @Valid Link link,
        @NotNull @Valid Jwt jwt
) {

    public record Link(
            @Min(3) @Max(20) int codeLength,
            @Min(1) int maxUrlLength,
            @Min(1) int maxCollisionRetries,
            @NotNull List<String> reservedAliases
    ) {
        public Link {
            reservedAliases = reservedAliases == null ? List.of() : List.copyOf(reservedAliases);
        }
    }

    public record Jwt(
            @NotBlank String issuer,
            @NotBlank @Size(min = 32) String secret,
            @NotNull Duration accessTokenTtl
    ) {
    }
}
