package com.electrahub.aisupport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "electrahub.ai-support.multitenancy")
public record TenantAiProperties(
        boolean enabled,
        boolean quotaEnabled,
        boolean quotaFailClosed,
        int defaultRequestsPerMinute,
        int defaultRequestsPerDay,
        String policiesJson
) {
}
