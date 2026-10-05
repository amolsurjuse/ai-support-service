package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.*;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportMcpClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final IdentityContext identity = new IdentityContext("tenant-a", "support-agent", Set.of("SUPPORT"), true);
    private final AtomicInteger calls = new AtomicInteger();
    private final String secret = "test-only-shared-identity-secret-32-chars";
    private HttpServer server;
    private SupportMcpClient client;
    private volatile String failure;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer agent-token");
            assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")).isEqualTo(SupportMcpClient.PROTOCOL);
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            exchange.getRequestHeaders().forEach((name, values) -> request.addHeader(name, values.getFirst()));
            var resolved = new TrustedIdentityContextResolver(mapper, secret, true, "test").resolve(request);
            assertThat(resolved).isEqualTo(identity);
            var envelope = mapper.readTree(exchange.getRequestBody().readAllBytes());
            if ("notifications/initialized".equals(envelope.path("method").asText())) {
                exchange.sendResponseHeaders(202, -1); exchange.close(); return;
            }
            Object result = "initialize".equals(envelope.path("method").asText())
                    ? Map.of("protocolVersion", SupportMcpClient.PROTOCOL, "capabilities", Map.of("tools", Map.of()))
                    : Map.of("isError", "tool-error".equals(failure), "content", List.of(), "structuredContent", Map.of("flowId", "charging-session"));
            byte[] body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id",
                    "wrong-id".equals(failure) ? "other" : envelope.path("id").asText(), "result", result));
            if ("oversize".equals(failure)) body = new byte[270000];
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if ("stalled".equals(failure)) {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { Thread.sleep(700); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.close();
                return;
            }
            if ("redirect".equals(failure)) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        client = new SupportMcpClient(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", mapper,
                new TrustedIdentityContextSigner(mapper, secret), new AiToolAuthorizationService(), mock(AiAuditService.class));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void negotiatesThenPassesSignedIdentityAndOriginalBearerOnEveryToolRead() {
        client.initialize(identity, "Bearer agent-token", Duration.ofSeconds(2));
        assertThat(client.call("get_flow_definition", Map.of("flowId", "charging-session"), identity,
                "Bearer agent-token", Duration.ofSeconds(2)).path("flowId").asText()).isEqualTo("charging-session");
        assertThat(calls).hasValue(3);
    }

    @Test void rejectsInvalidRolesToolNamesAndInjectedArgumentsBeforeTransport() {
        assertThatThrownBy(() -> client.call("get_service_topology", Map.of(),
                new IdentityContext("tenant-a", "reader", Set.of("ADMIN_READ_ONLY"), true), "Bearer agent-token", Duration.ofSeconds(1)))
                .hasMessageContaining("403");
        assertThatThrownBy(() -> client.call("execute_refund", Map.of(), identity, "Bearer agent-token", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.call("get_service_topology", Map.of("url", "http://attacker"), identity, "Bearer agent-token", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void rejectsMismatchToolFailureRedirectAndOversizedResponse() {
        for (String fault : List.of("wrong-id", "tool-error", "redirect", "oversize")) {
            failure = fault;
            assertThatThrownBy(() -> client.call("get_flow_definition", Map.of("flowId", "charging-session"), identity,
                    "Bearer agent-token", Duration.ofSeconds(2))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls).hasValue(4);
    }

    @Test void wholeBodyDeadlineStopsAResponseThatStallsAfterHeaders() {
        failure = "stalled";
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.call("get_flow_definition", Map.of("flowId", "charging-session"), identity,
                "Bearer agent-token", Duration.ofMillis(150))).isInstanceOf(IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(650));
    }
}
