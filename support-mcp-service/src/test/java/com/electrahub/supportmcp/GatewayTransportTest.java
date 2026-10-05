package com.electrahub.supportmcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class GatewayTransportTest {
    final ObjectMapper mapper = new ObjectMapper();
    final UUID session = UUID.fromString("12db94ed-258e-4569-842e-003395b74582");
    final TrustedSupportIdentity.Identity identity = new TrustedSupportIdentity.Identity("tenant-a", "support-a", Set.of("SUPPORT"), "Bearer original-customer-token");

    @Test void forwardsOnlyOriginalBearerToFixedGatewayAndValidatesSessionId() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>(), bearer = new AtomicReference<>();
        AtomicReference<String> responseId = new AtomicReference<>(session.toString());
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString()); bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst(TrustedSupportIdentity.CONTEXT)).isNull();
            byte[] body = mapper.writeValueAsBytes(Map.of("sessionId", responseId.get(), "collectedAt", "2026-10-05T00:00:00Z", "facts", List.of(), "gaps", List.of()));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new GatewayEvidenceClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(client.session(session, identity).path("sessionId").asText()).isEqualTo(session.toString());
            assertThat(path.get()).isEqualTo("/session/api/v1/sessions/admin/" + session + "/diagnostics");
            assertThat(bearer.get()).isEqualTo(identity.bearer());
            responseId.set(UUID.randomUUID().toString());
            assertThatThrownBy(() -> client.session(session, identity)).isInstanceOf(GatewayEvidenceClient.EvidenceUnavailable.class);
        } finally { server.stop(0); }
    }

    @Test void doesNotFollowRedirectsOrLeakBackendDenialBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/session/", exchange -> { exchange.getResponseHeaders().set("Location", "/leak"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/leak", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var client = new GatewayEvidenceClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> client.session(session, identity)).isInstanceOf(GatewayEvidenceClient.EvidenceUnavailable.class);
            assertThat(redirected).hasValue(0);
            assertThat(mapper.writeValueAsString(SupportTools.unavailable())).doesNotContain("302", "localhost", "token");
        } finally { server.stop(0); }
        assertThatThrownBy(() -> new GatewayEvidenceClient(mapper, "https://user:password@gateway")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayEvidenceClient(mapper, "https://gateway/arbitrary")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void boundedSubscriberCancelsBeforeBufferingOversizedChunk() {
        var body = new BoundedHttp.Body(4);
        AtomicBoolean cancelled = new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long n) {}
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.wrap("abcdef".getBytes(StandardCharsets.UTF_8))));
        assertThat(cancelled).isTrue();
        assertThatThrownBy(() -> body.getBody().toCompletableFuture().join()).isInstanceOf(CompletionException.class);
    }

    @Test void hardDeadlineIncludesAStalledBodyAfterHeaders() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1); var headers = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('x'); exchange.getResponseBody().flush(); headers.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                    .timeout(Duration.ofMillis(500)).GET().build();
            var future = executor.submit(() -> BoundedHttp.send(HttpClient.newHttpClient(), request, 1024));
            assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        } finally { release.countDown(); server.stop(0); }
    }
}
