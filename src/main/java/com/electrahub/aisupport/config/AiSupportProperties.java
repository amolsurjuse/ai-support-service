package com.electrahub.aisupport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "electrahub.ai-support")
public record AiSupportProperties(
        boolean providerEnabled,
        String provider,
        String supportEmail,
        long streamTokenDelayMs,
        String sessionServiceUrl,
        String paymentServiceUrl,
        String chargerServiceUrl,
        String ocppServiceUrl,
        int diagnosticsTimeoutMs,
        String openaiApiKey,
        String openaiBaseUrl,
        String ollamaBaseUrl,
        String model,
        double temperature,
        int maxOutputTokens,
        int llmTimeoutMs
) {
}
