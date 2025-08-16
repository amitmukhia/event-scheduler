package com.fourkites.event.scheduler.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger/OpenAPI configuration for Event Scheduler Pro API documentation. */
@Configuration
public class SwaggerConfig {

  private final String applicationName;
  private final String serverPort;

  public SwaggerConfig(AppProperties appProps) {
    this.applicationName = appProps.swagger() != null && appProps.swagger().applicationName() != null ? appProps.swagger().applicationName() : "event-scheduler-pro";
    this.serverPort = appProps.swagger() != null && appProps.swagger().serverPort() != null ? appProps.swagger().serverPort() : "8080";
  }

  @Bean
  public OpenAPI customOpenAPI() {
    return new OpenAPI()
        .info(apiInfo())
        .servers(
            List.of(
                new Server()
                    .url("http://localhost:" + serverPort)
                    .description("Local Development Server"),
                new Server().url("https://api.example.com").description("Production Server")))
        .tags(
            List.of(
                new Tag().name("Events").description("Event scheduling and management operations"),
                new Tag().name("Customers").description("Customer management operations"),
                new Tag().name("Watchdog").description("System monitoring and health checks"),
                new Tag().name("Cleanup").description("System maintenance and cleanup operations")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer-jwt",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT authentication token"))
                .addSecuritySchemes(
                    "api-key",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description("API Key authentication")));
  }

  private Info apiInfo() {
    return new Info()
        .title("Event Scheduler Pro API")
        .description(
            """
                        ## Overview
                        Event Scheduler Pro is a high-performance distributed event scheduling system built with Spring Boot.

                        ### Key Features:
                        - **UUID-based Event Management**: Unique identifiers for all entities
                        - **Recurring Events**: Support for complex recurrence patterns using cron expressions
                        - **Multi-channel Delivery**: Kafka and SQS message broker integration
                        - **Redis-based Orchestration**: High-throughput event processing with Redis TTL
                        - **Customer Isolation**: Per-customer event processing and monitoring
                        - **Automatic Retry & Watchdog**: Built-in resilience with stuck event detection

                        ### Event Lifecycle:
                        1. **SCHEDULED**: Event created and waiting for trigger time
                        2. **READY**: Event ready for processing (trigger time reached)
                        3. **PROCESSING**: Event being processed by worker
                        4. **COMPLETED**: Event successfully processed
                        5. **FAILED**: Event processing failed after retries
                        6. **CANCELLED**: Event cancelled by user

                        ### Rate Limits:
                        - Max 1000 events per minute per customer
                        - Batch operations limited to 100 items
                        """)
        .version("v1.0.0")
        .contact(
            new Contact()
                .name("Event Scheduler Team")
                .email("support@example.com")
                .url("https://github.com/example/event-scheduler-pro"))
        .license(
            new License()
                .name("Apache 2.0")
                .url("https://www.apache.org/licenses/LICENSE-2.0.html"));
  }
}
