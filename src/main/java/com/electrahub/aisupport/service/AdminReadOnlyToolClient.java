package com.electrahub.aisupport.service;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class AdminReadOnlyToolClient {
    private static final int RESULT_LIMIT = 20;
    private final RestClient gateway;

    @Autowired
    public AdminReadOnlyToolClient(RestClient.Builder builder,
                                   @Value("${electrahub.ai-support.gateway-url:http://api-gateway:8090}") String gatewayUrl,
                                   @Value("${electrahub.ai-support.admin-tool-timeout-ms:4000}") int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.gateway = builder.baseUrl(gatewayUrl).requestFactory(requestFactory).build();
    }

    AdminReadOnlyToolClient(RestClient.Builder builder, String gatewayUrl) {
        this(builder, gatewayUrl, 4000);
    }

    JsonNode execute(AdminToolRegistry.ToolDefinition tool,
                     AdminCommandPlanner.Plan plan,
                     String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalArgumentException("An administrator bearer token is required.");
        }
        return gateway.get()
                .uri(builder -> {
                    builder.path(tool.path());
                    switch (plan.toolId()) {
                        case CHARGERS_LIST, USERS_LIST -> builder.queryParam("limit", RESULT_LIMIT);
                        case SESSIONS_SEARCH -> {
                            builder.queryParam("size", RESULT_LIMIT);
                            if (plan.queryName() != null) {
                                builder.queryParam(plan.queryName(), plan.queryValue());
                            }
                        }
                        default -> { }
                    }
                    return builder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve()
                .body(JsonNode.class);
    }

    String summarize(AdminToolRegistry.ToolDefinition tool, JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "The scoped service returned no data.";
        }
        return switch (tool.auditName()) {
            case "admin.analytics.overview" -> analyticsSummary(payload);
            case "admin.sessions.success-rate" -> successRateSummary(payload);
            case "admin.chargers.status-summary" -> connectorStatusSummary(payload);
            case "admin.sessions.search" -> pagedSummary("session", payload);
            case "admin.chargers.list" -> pagedSummary("charger", payload);
            case "admin.users.list" -> pagedSummary("user", payload);
            default -> "The scoped query completed successfully.";
        };
    }

    private static String analyticsSummary(JsonNode node) {
        return "Revenue: %s %s; energy: %s kWh; sessions: %s; unique users: %s; average session: %s kWh."
                .formatted(text(node, "totalRevenue"), text(node, "currency"), text(node, "totalEnergyKwh"),
                        text(node, "totalSessions"), text(node, "uniqueUsers"), text(node, "avgSessionKwh"));
    }

    private static String successRateSummary(JsonNode node) {
        return "Charging success rate: %s%% (%s successful, %s failed, %s eligible sessions)."
                .formatted(text(node, "chargingSuccessRate"), text(node, "successfulSessions"),
                        text(node, "failedSessions"), text(node, "eligibleSessions"));
    }

    private static String connectorStatusSummary(JsonNode node) {
        StringBuilder summary = new StringBuilder("Connector fleet: ").append(text(node, "total")).append(" total");
        JsonNode statuses = node.path("statuses");
        if (statuses.isArray()) {
            for (JsonNode status : statuses) {
                summary.append("; ").append(text(status, "status")).append(": ").append(text(status, "count"));
            }
        }
        return summary.append('.').toString();
    }

    private static String pagedSummary(String entity, JsonNode node) {
        JsonNode rows = firstArray(node, List.of("content", "items", "results", "chargers", "users"));
        int shown = rows == null ? 0 : rows.size();
        String total = firstText(node, List.of("totalElements", "total", "totalCount", "count"));
        if (total == null) {
            total = Integer.toString(shown);
        }
        if (shown == 0) {
            return "No scoped " + entity + " records matched the query.";
        }
        return "Found " + total + " scoped " + entity + " record(s); showing " + shown + ".";
    }

    private static JsonNode firstArray(JsonNode node, List<String> names) {
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        return node.isArray() ? node : null;
    }

    private static String firstText(JsonNode node, List<String> names) {
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (!candidate.isMissingNode() && !candidate.isNull()) {
                return candidate.asText();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "n/a" : value.asText();
    }
}
