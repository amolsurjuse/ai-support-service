package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import com.electrahub.aisupport.security.TrustedIdentityContextSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Private, stateless MCP transport. Neither model output nor browser context can choose a URL. */
@Component
public class SupportMcpClient {
    static final String PROTOCOL = "2025-11-25";
    static final Set<String> TOOLS = Set.of("get_session_evidence", "get_org_context",
            "get_flow_definition", "get_service_topology");
    private static final int MAX_BYTES = 262144;
    private final URI endpoint;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final TrustedIdentityContextSigner signer;
    private final AiToolAuthorizationService authorization;
    private final AiAuditService audit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public SupportMcpClient(@Value("${electrahub.ai-support.mcp.enabled:false}") boolean enabled,
                            @Value("${electrahub.ai-support.mcp.url:http://support-mcp-service:8095/mcp}") String url,
                            ObjectMapper mapper, TrustedIdentityContextSigner signer,
                            AiToolAuthorizationService authorization, AiAuditService audit) {
        this.enabled = enabled;
        this.endpoint = URI.create(url);
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || !"/mcp".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("MCP URL must be an absolute HTTP(S) /mcp endpoint without credentials or query");
        }
        this.mapper = mapper;
        this.signer = signer;
        this.authorization = authorization;
        this.audit = audit;
    }

    public boolean enabled() { return enabled; }

    void initialize(IdentityContext identity, String bearer, Duration timeout) {
        JsonNode result = exchange("initialize", Map.of("protocolVersion", PROTOCOL,
                "capabilities", Map.of(), "clientInfo", Map.of("name", "sparky-support-host", "version", "1.0.0")),
                identity, bearer, timeout);
        if (!PROTOCOL.equals(result.path("protocolVersion").asText()) || !result.path("capabilities").has("tools")) {
            throw new IllegalStateException("MCP protocol or tool capability mismatch");
        }
        // Stateless server: no session cookie or initialization state is retained across users.
        notifyInitialized(identity, bearer, timeout);
    }

    JsonNode call(String tool, Map<String, String> arguments, IdentityContext identity, String bearer, Duration timeout) {
        authorization.requireSupportAnalysis(identity);
        if (!TOOLS.contains(tool)) throw new IllegalArgumentException("Unsupported support tool");
        validateArguments(tool, arguments);
        long started = System.nanoTime();
        String outcome = "FAILURE";
        try {
            JsonNode result = exchange("tools/call", Map.of("name", tool, "arguments", arguments), identity, bearer, timeout);
            if (result.path("isError").asBoolean(false))
                throw new EvidenceFailure(FailureReason.parse(result.path("structuredContent").path("reason").asText()));
            if (!result.path("structuredContent").isObject()) throw new EvidenceFailure(FailureReason.UNAVAILABLE);
            outcome = "SUCCESS";
            return result.path("structuredContent");
        } finally {
            audit.diagnosticCompleted(identity, "mcp." + tool,
                    (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), outcome);
        }
    }

    private static void validateArguments(String tool, Map<String, String> args) {
        switch (tool) {
            case "get_session_evidence", "get_org_context" -> {
                if (!args.keySet().equals(Set.of("sessionId"))) throw new IllegalArgumentException("Invalid session arguments");
                UUID id = UUID.fromString(args.get("sessionId"));
                if (!id.toString().equalsIgnoreCase(args.get("sessionId"))) throw new IllegalArgumentException("Invalid session UUID");
            }
            case "get_flow_definition" -> {
                if (!args.equals(Map.of("flowId", "charging-session"))) throw new IllegalArgumentException("Invalid flow");
            }
            case "get_service_topology" -> {
                if (!args.isEmpty()) throw new IllegalArgumentException("Topology takes no arguments");
            }
            default -> throw new IllegalArgumentException("Unsupported support tool");
        }
    }

    private JsonNode exchange(String method, Map<String, ?> params, IdentityContext identity, String bearer, Duration timeout) {
        String id = UUID.randomUUID().toString();
        byte[] bytes = send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params), identity, bearer, timeout, false);
        JsonNode response = mapper.readTree(bytes);
        if (!"2.0".equals(response.path("jsonrpc").asText()) || !id.equals(response.path("id").asText())
                || response.has("error") || !response.path("result").isObject()) {
            throw new IllegalStateException("Invalid MCP response");
        }
        return response.path("result");
    }

    private void notifyInitialized(IdentityContext identity, String bearer, Duration timeout) {
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), identity, bearer, timeout, true);
    }

    private byte[] send(Map<String, ?> envelope, IdentityContext identity, String bearer, Duration timeout, boolean notification) {
        authorization.requireSupportAnalysis(identity);
        if (!enabled) throw new IllegalStateException("MCP is disabled");
        if (bearer == null || !bearer.matches("(?i)Bearer [^\\s]+")) throw new IllegalArgumentException("Bearer token required");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", bearer).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", PROTOCOL)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(envelope)));
            signer.apply(request, identity);
            pending = http.sendAsync(request.build(), ignored -> new BoundedBody(MAX_BYTES));
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != (notification ? 202 : 200)) {
                throw new EvidenceFailure(switch (response.statusCode()) {
                    case 401, 403 -> FailureReason.ACCESS_DENIED;
                    case 408, 504 -> FailureReason.TIMEOUT;
                    default -> FailureReason.UNAVAILABLE;
                });
            }
            if (!notification && !response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
                throw new IllegalStateException("Unsupported MCP response content type");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP request interrupted", e);
        } catch (TimeoutException e) {
            throw new EvidenceFailure(FailureReason.TIMEOUT);
        } catch (ExecutionException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof HttpTimeoutException) throw new EvidenceFailure(FailureReason.TIMEOUT);
            throw new EvidenceFailure(FailureReason.UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    enum FailureReason {
        TIMEOUT, ACCESS_DENIED, NOT_FOUND, UNAVAILABLE;
        static FailureReason parse(String value) {
            try { return valueOf(value); } catch (RuntimeException ex) { return UNAVAILABLE; }
        }
    }
    static final class EvidenceFailure extends IllegalStateException {
        final FailureReason reason;
        EvidenceFailure(FailureReason reason) { super("Support evidence unavailable: " + reason); this.reason = reason; }
    }

    /** Enforces the cap during reception, before the HTTP client can buffer an unbounded body. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("MCP response exceeded size limit"));
                    return;
                }
                byte[] data = new byte[chunk.remaining()];
                chunk.get(data);
                bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
