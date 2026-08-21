package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/** Read-only, role-gated BookStack search used as supplemental knowledge only. */
@Component
class BookStackKnowledgeClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String tokenId;
    private final String tokenSecret;
    private final boolean enabled;

    BookStackKnowledgeClient(ObjectMapper mapper,
                             @Value("${BOOKSTACK_BASE_URL:}") String baseUrl,
                             @Value("${BOOKSTACK_TOKEN_ID:}") String tokenId,
                             @Value("${BOOKSTACK_TOKEN_SECRET:}") String tokenSecret,
                             @Value("${AI_BOOKSTACK_ENABLED:false}") boolean enabled) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.tokenId = tokenId == null ? "" : tokenId.trim();
        this.tokenSecret = tokenSecret == null ? "" : tokenSecret.trim();
        this.enabled = enabled;
    }

    String search(String query, IdentityContext identity) {
        if (!enabled || identity == null || identity.roles() == null ||
                Set.of("SYSTEM_ADMIN", "ADMIN_READ_ONLY", "ENTERPRISE", "NETWORK", "LOCATION")
                        .stream().noneMatch(identity.roles()::contains) ||
                baseUrl.isBlank() || tokenId.isBlank() || tokenSecret.isBlank() || query == null || query.isBlank()) {
            return "";
        }
        try {
            String url = baseUrl.replaceAll("/$", "") + "/api/search?query=" +
                    java.net.URLEncoder.encode(query.substring(0, Math.min(180, query.length())), java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Token " + tokenId + ":" + tokenSecret)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return "";
            JsonNode items = mapper.readTree(response.body()).path("data");
            StringBuilder result = new StringBuilder();
            for (JsonNode item : items) {
                if (result.length() > 1600) break;
                result.append("- ").append(item.path("name").asText("BookStack page"));
                String html = item.path("content").asText("");
                if (!html.isBlank()) result.append(": ").append(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                result.append("\n");
            }
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
