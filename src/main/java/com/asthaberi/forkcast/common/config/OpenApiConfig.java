package com.asthaberi.forkcast.common.config;

import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description at /v3/api-docs and Swagger UI at /swagger-ui.html, generated from the controllers.
 * Every endpoint needs a bearer token unless it's marked with an empty @SecurityRequirements.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearer-jwt";
    private static final String PROBLEM = "Problem";

    @Bean
    OpenAPI forkcastOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Forkcast API")
                        .version("v1")
                        .description("""
                                Food delivery backend: restaurant discovery, menus, cart, orders and live order tracking.

                                **Authentication:** call `POST /api/v1/auth/login`, then click **Authorize** and paste the
                                `accessToken`. Local demo accounts: `customer@forkcast.example`, `owner@forkcast.example`,
                                `admin@forkcast.example` (passwords in `application.yml`).

                                **Errors** use RFC 9457 Problem Details (`application/problem+json`); business-rule
                                violations include a machine-readable `code`.""")
                        .contact(new Contact().name("Astha Beri").url("https://github.com/asthaberi")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login"))
                        .addSchemas(PROBLEM, problemSchema()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * Adds the error responses every endpoint can return, so clients see them in the spec without each
     * controller method repeating them. 401/403 only on endpoints that need a token.
     */
    @Bean
    OpenApiCustomizer standardErrorResponses() {
        return openApi -> openApi.getPaths().forEach((url, path) -> path.readOperations().forEach(operation -> {
            addProblem(operation, "400", "Invalid request (validation errors are listed under `errors`)");
            addProblem(operation, "429", "Rate limit exceeded; see the `Retry-After` header");
            if (requiresToken(operation, openApi)) {
                addProblem(operation, "401", "Missing, invalid or expired access token");
                addProblem(operation, "403", "Signed in, but not allowed to do this");
            }
            if (url.contains("{")) {
                addProblem(operation, "404", "No such resource (or it isn't yours)");
            }
            // Errors documented on a controller method would otherwise inherit its success body type
            operation.getResponses().forEach((status, response) -> {
                if (status.startsWith("4")) {
                    response.setContent(problemContent());
                }
            });
            // A documented 201/204 replaces the default 200 springdoc derives from the return type
            if (operation.getResponses().keySet().stream().anyMatch(s -> s.equals("201") || s.equals("204"))
                    && !hasDocumented200(operation)) {
                operation.getResponses().remove("200");
            }
        }));
    }

    private static boolean hasDocumented200(Operation operation) {
        ApiResponse ok = operation.getResponses().get("200");
        return ok != null && ok.getDescription() != null && ok.getDescription().startsWith("Idempotent replay");
    }

    private static Content problemContent() {
        return new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)));
    }

    private static boolean requiresToken(Operation operation, OpenAPI openApi) {
        // An operation-level empty list means "public"; no list means the global bearer requirement applies
        return operation.getSecurity() == null ? !openApi.getSecurity().isEmpty() : !operation.getSecurity().isEmpty();
    }

    private static void addProblem(Operation operation, String status, String description) {
        if (operation.getResponses().containsKey(status)) {
            return;
        }
        operation.getResponses().addApiResponse(status, new ApiResponse()
                .description(description)
                .content(problemContent()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("RFC 9457 Problem Details")
                .properties((Map) Map.of(
                        "status", new IntegerSchema().example(409),
                        "title", new StringSchema().example("Cart contains items from another restaurant"),
                        "detail", new StringSchema().example("Your cart has items from Dosa Junction."),
                        "instance", new StringSchema().example("/api/v1/cart/items"),
                        "code", new StringSchema().example("CART_RESTAURANT_MISMATCH")
                                .description("Business-rule code, when applicable"),
                        "errors", new MapSchema().additionalProperties(new StringSchema())
                                .description("Field-level validation errors, when applicable")));
    }
}
