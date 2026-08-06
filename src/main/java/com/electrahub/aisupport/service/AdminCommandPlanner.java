package com.electrahub.aisupport.service;

import com.electrahub.aisupport.service.AdminToolRegistry.ToolId;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class AdminCommandPlanner {
    private static final Pattern MUTATION = Pattern.compile(
            "\\b(create|add|delete|remove|disable|enable|update|edit|change|assign|unassign|refund|stop|start|reset|cancel|approve|reject|suspend|activate|deactivate)\\b",
            Pattern.CASE_INSENSITIVE);

    public Optional<Plan> plan(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        if (MUTATION.matcher(normalized).find()) {
            return Optional.of(Plan.mutationBlocked());
        }
        if (containsAny(normalized, "charging success", "success rate", "csr")) {
            return Optional.of(Plan.read(ToolId.CHARGING_SUCCESS_RATE));
        }
        if (containsAny(normalized, "revenue", "sales", "income", "analytics overview", "dashboard overview", "total energy", "unique users")) {
            return Optional.of(Plan.read(ToolId.ANALYTICS_OVERVIEW));
        }
        if (containsAny(normalized, "connector status", "fleet status")
                || (containsAny(normalized, "offline", "online", "faulted")
                && containsAny(normalized, "charger", "connector", "station", "evse"))) {
            return Optional.of(Plan.read(ToolId.CONNECTOR_STATUS_SUMMARY));
        }
        if (containsAny(normalized, "charger", "connector", "station", "evse")) {
            return Optional.of(Plan.read(ToolId.CHARGERS_LIST));
        }
        if (containsAny(normalized, "session", "charging history", "failed charge", "active charge", "stuck charge")) {
            String state = containsAny(normalized, "failed", "failure") ? "FAILED"
                    : containsAny(normalized, "completed", "complete", "history") ? "COMPLETED" : "ACTIVE";
            return Optional.of(Plan.read(ToolId.SESSIONS_SEARCH, "state", state));
        }
        if (containsAny(normalized, "user", "driver", "account")) {
            return Optional.of(Plan.read(ToolId.USERS_LIST));
        }
        return Optional.empty();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public record Plan(ToolId toolId, boolean mutation, String queryName, String queryValue) {
        static Plan read(ToolId toolId) {
            return new Plan(toolId, false, null, null);
        }

        static Plan read(ToolId toolId, String queryName, String queryValue) {
            return new Plan(toolId, false, queryName, queryValue);
        }

        static Plan mutationBlocked() {
            return new Plan(null, true, null, null);
        }
    }
}
