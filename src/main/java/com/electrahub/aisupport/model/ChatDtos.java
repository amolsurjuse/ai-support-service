package com.electrahub.aisupport.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ChatDtos {
    private ChatDtos() {
    }

    public record SendMessageRequest(
            UUID threadId,
            @NotBlank @Size(max = 4_000) String content,
            @Valid ContextPayload context
    ) {
    }

    public record ContextPayload(
            @Size(max = 80) String screen,
            @Size(max = 80) String resourceType,
            @Size(max = 160) String resourceId,
            @Size(max = 160) String chargerId,
            @Size(max = 160) String connectorId,
            @Size(max = 160) String locationId,
            @Size(max = 160) String sessionId,
            @Size(max = 40) String audience,
            @Size(max = 24) Map<String, String> attributes
    ) {
        public ContextPayload(String screen, String resourceType, String resourceId, String chargerId,
                              String connectorId, String locationId, String sessionId, String audience) {
            this(screen, resourceType, resourceId, chargerId, connectorId, locationId, sessionId, audience, Map.of());
        }

        public boolean driverAudience() {
            return audience == null || audience.isBlank() || "driver".equalsIgnoreCase(audience);
        }
    }

    public record SendMessageResponse(
            UUID threadId,
            UUID messageId,
            String answer,
            String tool,
            String contextSummary
    ) {
    }

    public record StreamEvent(
            String type,
            UUID messageId,
            String delta,
            String tool,
            Boolean ok,
            Integer latencyMs,
            String code,
            String message,
            Instant emittedAt
    ) {
        public static StreamEvent token(UUID messageId, String delta) {
            return new StreamEvent("TOKEN", messageId, delta, null, null, null, null, null, Instant.now());
        }

        public static StreamEvent toolCall(UUID messageId, String tool) {
            return new StreamEvent("TOOL_CALL", messageId, null, tool, null, null, null, null, Instant.now());
        }

        public static StreamEvent toolResult(UUID messageId, String tool, boolean ok, int latencyMs) {
            return new StreamEvent("TOOL_RESULT", messageId, null, tool, ok, latencyMs, null, null, Instant.now());
        }

        public static StreamEvent done(UUID messageId) {
            return new StreamEvent("DONE", messageId, null, null, null, null, null, null, Instant.now());
        }

        public static StreamEvent error(UUID messageId, String code, String message) {
            return new StreamEvent("ERROR", messageId, null, null, false, null, code, message, Instant.now());
        }
    }
}
