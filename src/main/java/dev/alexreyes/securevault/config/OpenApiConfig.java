package dev.alexreyes.securevault.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. Declares JWT bearer auth and applies it to every
 * operation by default; public endpoints opt out with an empty
 * {@code @SecurityRequirements}.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI secureVaultOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SecureVault API")
                        .version("0.1.0")
                        .description("Security-focused document management API. Obtain a token from "
                                + "POST /api/auth/login and send it as 'Authorization: Bearer <token>'."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
