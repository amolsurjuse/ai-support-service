package com.electrahub.aisupport.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminReadOnlyToolClientTest {
    private final AdminToolRegistry registry = new AdminToolRegistry();

    @Test
    void callsOnlyTheRegisteredGatewayGetAndForwardsBearerToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/session/api/v1/sessions/admin/search", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getQuery());
            byte[] body = "{\"content\":[{\"id\":\"one\"}],\"totalElements\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AdminReadOnlyToolClient client = new AdminReadOnlyToolClient(
                    RestClient.builder(), "http://localhost:" + server.getAddress().getPort());
            AdminCommandPlanner.Plan plan = new AdminCommandPlanner().plan("show failed sessions").orElseThrow();
            var payload = client.execute(registry.require(plan.toolId()), plan, "Bearer scoped-token");

            assertThat(method.get()).isEqualTo("GET");
            assertThat(authorization.get()).isEqualTo("Bearer scoped-token");
            assertThat(query.get()).contains("size=20", "state=FAILED");
            assertThat(client.summarize(registry.require(plan.toolId()), payload))
                    .isEqualTo("Found 1 scoped session record(s); showing 1.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCallsWithoutBearerToken() {
        AdminReadOnlyToolClient client = new AdminReadOnlyToolClient(RestClient.builder(), "http://localhost:1");
        AdminCommandPlanner.Plan plan = new AdminCommandPlanner().plan("show revenue").orElseThrow();
        assertThatThrownBy(() -> client.execute(registry.require(plan.toolId()), plan, "Basic unsafe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bearer token");
    }

    @Test
    void summarizesAnalyticsWithoutReturningRawPayload() throws Exception {
        AdminReadOnlyToolClient client = new AdminReadOnlyToolClient(RestClient.builder(), "http://localhost:1");
        var payload = new ObjectMapper().readTree("""
                {"totalRevenue":120.50,"currency":"USD","totalEnergyKwh":400,"totalSessions":12,
                 "uniqueUsers":8,"avgSessionKwh":33.3,"privateField":"must-not-leak"}
                """);
        String summary = client.summarize(registry.require(AdminToolRegistry.ToolId.ANALYTICS_OVERVIEW), payload);
        assertThat(summary).contains("Revenue: 120.5 USD", "sessions: 12").doesNotContain("privateField", "must-not-leak");
    }
}
