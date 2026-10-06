package com.electrahub.supportmcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.net.http.HttpTimeoutException;
import org.slf4j.LoggerFactory;

@Component
public class GatewayEvidenceClient {
    // The gateway allows its session-service request twelve seconds. Keep the outer
    // deadline longer so a valid, slower diagnostic report is not discarded first.
    static final Duration EVIDENCE_TIMEOUT = Duration.ofSeconds(14);
    private final ObjectMapper mapper;
    private final URI gateway;
    private final HttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayEvidenceClient(ObjectMapper mapper, @Value("${support-mcp.gateway-url}") String gateway) {
        this(mapper, URI.create(gateway), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    GatewayEvidenceClient(ObjectMapper mapper, URI gateway, HttpClient client) {
        if (!SetOfSchemes.allowed(gateway) || gateway.getUserInfo() != null || gateway.getQuery() != null
                || gateway.getFragment() != null || (gateway.getPath() != null && !gateway.getPath().matches("/?")))
            throw new IllegalArgumentException("Gateway must be a fixed http(s) origin without user info or path.");
        this.mapper = mapper; this.gateway = gateway; this.client = client;
    }

    JsonNode session(UUID sessionId, TrustedSupportIdentity.Identity identity) {
        return read("/session/api/v1/sessions/admin/" + sessionId + "/diagnostics", sessionId, identity);
    }

    private JsonNode read(String path, UUID sessionId, TrustedSupportIdentity.Identity identity) {
        var request = HttpRequest.newBuilder(gateway.resolve(path)).timeout(EVIDENCE_TIMEOUT)
                .header("Authorization", identity.bearer()).header("Accept", "application/json").GET().build();
        try {
            var response = BoundedHttp.send(client, request, 196_608);
                if (response.statusCode() != 200) throw new EvidenceUnavailable(switch (response.statusCode()) {
                    case 401, 403 -> FailureReason.ACCESS_DENIED;
                    case 404 -> FailureReason.NOT_FOUND;
                    case 408, 504 -> FailureReason.TIMEOUT;
                    default -> FailureReason.UNAVAILABLE;
                });
                JsonNode body = mapper.readTree(response.body());
                if (!body.isObject() || !sessionId.toString().equals(body.path("sessionId").asText())) throw new EvidenceUnavailable();
                return body;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw unavailable(FailureReason.UNAVAILABLE);
        } catch (EvidenceUnavailable ex) { throw unavailable(ex.reason); }
        catch (Exception ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException)
                    throw unavailable(FailureReason.TIMEOUT);
            }
            throw unavailable(FailureReason.UNAVAILABLE);
        }
    }

    private static EvidenceUnavailable unavailable(FailureReason reason) {
        // No URL, session identifier, bearer, response body or exception message is logged.
        LoggerFactory.getLogger(GatewayEvidenceClient.class).info("Session evidence read unavailable reason={}", reason);
        return new EvidenceUnavailable(reason);
    }
    enum FailureReason { TIMEOUT, ACCESS_DENIED, NOT_FOUND, UNAVAILABLE }
    static class EvidenceUnavailable extends RuntimeException {
        final FailureReason reason;
        EvidenceUnavailable() { this(FailureReason.UNAVAILABLE); }
        EvidenceUnavailable(FailureReason reason) { this.reason = reason; }
    }
    private static class SetOfSchemes {
        static boolean allowed(URI uri) { return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null; }
    }
}
