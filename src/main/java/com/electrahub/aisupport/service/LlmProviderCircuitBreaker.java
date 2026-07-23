package com.electrahub.aisupport.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps an unavailable provider out of the synchronous request path for a short cooldown. */
final class LlmProviderCircuitBreaker {
    private final ConcurrentHashMap<String, Instant> unavailableUntil = new ConcurrentHashMap<>();
    private final Duration cooldown;

    LlmProviderCircuitBreaker(long cooldownMillis) {
        this.cooldown = Duration.ofMillis(Math.max(1_000L, cooldownMillis));
    }

    boolean allows(String provider) {
        Instant until = unavailableUntil.get(key(provider));
        if (until == null) {
            return true;
        }
        if (until.isAfter(Instant.now())) {
            return false;
        }
        unavailableUntil.remove(key(provider), until);
        return true;
    }

    void recordFailure(String provider) {
        unavailableUntil.put(key(provider), Instant.now().plus(cooldown));
    }

    void recordSuccess(String provider) {
        unavailableUntil.remove(key(provider));
    }

    private static String key(String provider) {
        return provider == null ? "" : provider.toLowerCase(Locale.ROOT);
    }
}
