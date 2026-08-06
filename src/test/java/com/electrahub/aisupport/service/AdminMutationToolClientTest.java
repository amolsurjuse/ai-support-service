package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AdminMutationToolClientTest {
    @Test
    void sendsAllowlistedPostWithBearerAndIdempotencyKey() throws Exception {
        UUID sessionId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/session/api/v1/sessions/admin/" + sessionId + "/stop", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var store = new AdminMutationApprovalStore(Duration.ofMinutes(5),
                    Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC));
            var approval = store.create(new IdentityContext("tenant-a", "admin-a", Set.of("SYSTEM_ADMIN"), true),
                    AdminMutationPlanner.Operation.STOP_SESSION, sessionId);
            var client = new AdminMutationToolClient(
                    RestClient.builder(), "http://localhost:" + server.getAddress().getPort(), 2000);

            client.execute(approval, "Bearer scoped-admin-token");

            assertThat(method.get()).isEqualTo("POST");
            assertThat(authorization.get()).isEqualTo("Bearer scoped-admin-token");
            assertThat(idempotency.get()).isEqualTo(approval.idempotencyKey());
            assertThat(body.get()).contains("AI_ADMIN_APPROVED", "userInitiated");
        } finally {
            server.stop(0);
        }
    }
}
