package com.electrahub.aisupport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "electrahub.ai-support")
public record AiSupportProperties(
        boolean providerEnabled,
        String supportEmail,
        long streamTokenDelayMs
) {
}
