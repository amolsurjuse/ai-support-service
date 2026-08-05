package com.electrahub.aisupport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "electrahub.ai-support.local-runtime")
public record LocalAiRuntimeProperties(
        String ollamaKeepAlive,
        boolean warmupEnabled,
        int warmupTimeoutMs,
        boolean ovmsEnabled,
        String ovmsBaseUrl,
        String ovmsModel,
        int ovmsTimeoutMs
) {
    public LocalAiRuntimeProperties {
        ollamaKeepAlive = defaultIfBlank(ollamaKeepAlive, "30m");
        warmupTimeoutMs = Math.max(1_000, warmupTimeoutMs);
        ovmsBaseUrl = defaultIfBlank(ovmsBaseUrl, "http://ovms:8000");
        ovmsModel = defaultIfBlank(ovmsModel, "OpenVINO/Qwen3-8B-int4-ov");
        ovmsTimeoutMs = Math.max(1_000, ovmsTimeoutMs);
    }

    public static LocalAiRuntimeProperties defaults() {
        return new LocalAiRuntimeProperties("30m", false, 30_000, false,
                "http://ovms:8000", "OpenVINO/Qwen3-8B-int4-ov", 12_000);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
