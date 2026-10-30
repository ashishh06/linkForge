package com.linkforge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. The bearer scheme is declared here so Swagger UI shows an
 * "Authorize" button once the JWT endpoints exist in Phase 1/2.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI linkForgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LinkForge API")
                        .version("v1")
                        .description("URL shortener with distributed rate limiting and click analytics"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
