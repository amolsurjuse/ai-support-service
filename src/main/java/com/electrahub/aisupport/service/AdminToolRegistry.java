package com.electrahub.aisupport.service;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Component
public class AdminToolRegistry {
    public enum ToolId {
        ANALYTICS_OVERVIEW,
        CHARGERS_LIST,
        CONNECTOR_STATUS_SUMMARY,
        SESSIONS_SEARCH,
        CHARGING_SUCCESS_RATE,
        USERS_LIST
    }

    public record ToolDefinition(String auditName, String path, String description) {
    }

    private final Map<ToolId, ToolDefinition> tools;

    public AdminToolRegistry() {
        EnumMap<ToolId, ToolDefinition> configured = new EnumMap<>(ToolId.class);
        configured.put(ToolId.ANALYTICS_OVERVIEW, new ToolDefinition(
                "admin.analytics.overview", "/billing/api/v1/admin/analytics/overview", "charging analytics overview"));
        configured.put(ToolId.CHARGERS_LIST, new ToolDefinition(
                "admin.chargers.list", "/charger/api/v1/admin/chargers/connectors", "charger and connector inventory"));
        configured.put(ToolId.CONNECTOR_STATUS_SUMMARY, new ToolDefinition(
                "admin.chargers.status-summary", "/billing/api/v1/admin/analytics/connector-status-summary", "connector fleet status"));
        configured.put(ToolId.SESSIONS_SEARCH, new ToolDefinition(
                "admin.sessions.search", "/session/api/v1/sessions/admin/search", "charging sessions"));
        configured.put(ToolId.CHARGING_SUCCESS_RATE, new ToolDefinition(
                "admin.sessions.success-rate", "/session/api/v1/sessions/admin/charging-success-rate", "charging success rate"));
        configured.put(ToolId.USERS_LIST, new ToolDefinition(
                "admin.users.list", "/user/api/v1/admin/users", "user directory"));
        this.tools = Map.copyOf(configured);
    }

    public ToolDefinition require(ToolId id) {
        return Optional.ofNullable(tools.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown admin tool: " + id));
    }

    public Map<ToolId, ToolDefinition> all() {
        return tools;
    }
}
