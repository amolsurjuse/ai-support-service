package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.TenantAiProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

@Service
public class TenantAiPolicyService {
    private static final TypeReference<Map<String, TenantOverride>> POLICY_TYPE = new TypeReference<>() { };

    private final TenantAiProperties properties;
    private final ObjectMapper objectMapper;
    private Map<String, TenantOverride> overrides = Map.of();

    public TenantAiPolicyService(TenantAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        String json = properties.policiesJson();
        if (json == null || json.isBlank()) {
            overrides = Map.of();
            return;
        }
        try {
            overrides = Map.copyOf(objectMapper.readValue(json, POLICY_TYPE));
        } catch (Exception ex) {
            throw new IllegalStateException("AI tenant policy configuration is invalid", ex);
        }
    }

    public TenantPolicy policyFor(String trustedTenantId) {
        String tenantId = normalizeTenant(trustedTenantId);
        TenantOverride override = overrides.get(tenantId);
        boolean enabled = properties.enabled() && (override == null || override.enabled() == null || override.enabled());
        int perMinute = positiveOrDefault(override == null ? null : override.requestsPerMinute(),
                properties.defaultRequestsPerMinute());
        int perDay = positiveOrDefault(override == null ? null : override.requestsPerDay(),
                properties.defaultRequestsPerDay());
        int tokensPerDay = positiveOrDefault(override == null ? null : override.tokensPerDay(),
                properties.defaultTokensPerDay());
        List<String> knowledge = override == null || override.knowledge() == null
                ? List.of() : override.knowledge().stream().filter(TenantAiPolicyService::hasText).limit(20).toList();
        Set<String> tools = override == null || override.allowedAdminTools() == null
                ? Set.of("*") : Set.copyOf(override.allowedAdminTools());
        return new TenantPolicy(tenantId, enabled, perMinute, perDay, tokensPerDay, knowledge, tools);
    }

    public boolean quotaEnabled() {
        return properties.enabled() && properties.quotaEnabled();
    }

    public boolean quotaFailClosed() {
        return properties.quotaFailClosed();
    }

    public boolean evaluationEnabled() {
        return properties.enabled() && properties.evaluationEnabled();
    }

    public List<String> evaluationTenants() {
        if (properties.evaluationTenants() == null || properties.evaluationTenants().isBlank()) {
            return List.of();
        }
        return Arrays.stream(properties.evaluationTenants().split(","))
                .map(String::trim)
                .filter(TenantAiPolicyService::hasText)
                .distinct()
                .toList();
    }

    private static String normalizeTenant(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new TenantAiAccessException("AI tenant identity is invalid");
        }
        return value;
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        int selected = value == null ? fallback : value;
        if (selected < 1) {
            throw new IllegalStateException("AI tenant quota values must be positive");
        }
        return selected;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record TenantPolicy(String tenantId, boolean enabled, int requestsPerMinute, int requestsPerDay,
                               int tokensPerDay,
                               List<String> knowledge, Set<String> allowedAdminTools) {
        public boolean allowsTool(String auditName) {
            return allowedAdminTools.contains("*") || allowedAdminTools.contains(auditName);
        }

        public String knowledgeText() {
            return knowledge.isEmpty() ? "" : String.join("\n", knowledge.stream().map(value -> "- " + value).toList());
        }
    }

    public record TenantOverride(Boolean enabled, Integer requestsPerMinute, Integer requestsPerDay,
                                 Integer tokensPerDay,
                                 List<String> knowledge, Set<String> allowedAdminTools) {
    }
}
