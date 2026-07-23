package com.electrahub.aisupport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
        int diagnosticsTotalTimeoutMs,
        long threadTtlMs,
        String openaiApiKey,
        String openaiBaseUrl,
        String ollamaBaseUrl,
        String model,
        double temperature,
        int maxOutputTokens,
        int llmTimeoutMs,
        String providerChain,
        boolean vllmEnabled,
        String vllmBaseUrl,
        String vllmModel,
        int vllmTimeoutMs,
        boolean ollamaEnabled,
        String ollamaModel,
        int ollamaTimeoutMs,
        int ollamaMaxConcurrentRequests,
        boolean geminiEnabled,
        boolean hostedFallbackEnabled,
        String geminiApiKey,
        String geminiBaseUrl,
        String geminiModel,
        int geminiTimeoutMs,
        long providerFailureCooldownMs
) {
    public List<String> providerOrder() {
        String configured = isBlank(providerChain) ? provider : providerChain;
        if (isBlank(configured)) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
