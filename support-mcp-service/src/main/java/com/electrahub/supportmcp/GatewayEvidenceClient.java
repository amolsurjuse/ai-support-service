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

@Component
public class GatewayEvidenceClient {
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
        var request = HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(6))
                .header("Authorization", identity.bearer()).header("Accept", "application/json").GET().build();
        try {
            var response = BoundedHttp.send(client, request, 196_608);
                if (response.statusCode() != 200) throw new EvidenceUnavailable();
                JsonNode body = mapper.readTree(response.body());
                if (!body.isObject() || !sessionId.toString().equals(body.path("sessionId").asText())) throw new EvidenceUnavailable();
                return body;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new EvidenceUnavailable(); }
        catch (Exception ex) { throw new EvidenceUnavailable(); }
    }

    static class EvidenceUnavailable extends RuntimeException {}
    private static class SetOfSchemes {
        static boolean allowed(URI uri) { return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null; }
    }
}
