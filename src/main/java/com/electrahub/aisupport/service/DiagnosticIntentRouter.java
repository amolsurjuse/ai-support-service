package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class DiagnosticIntentRouter {
    public static final String PAYMENT = "payment";
    public static final String SESSION = "session";
    public static final String CHARGER = "charger";
    public static final String OCPP_CONNECTION = "ocpp connection";
    public static final String OCPP_HISTORY = "ocpp history";
    public static final String SESSION_INVESTIGATION = "session investigation";

    public Set<String> route(String userMessage, ContextPayload context) {
        String responseMode = context == null || context.attributes() == null ? null : context.attributes().get("responseMode");
        if ("KNOWLEDGE".equals(responseMode) || "CHANGE_PRECHECK".equals(responseMode)) {
            return Set.of();
        }
        String message = normalize(userMessage);
        if (message.isBlank()) {
            return Set.of();
        }
        if (investigatesSession(userMessage, context)) return Set.of(SESSION_INVESTIGATION);

        LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        boolean receiptOrCost = containsAny(message,
                "receipt", "estimated cost", "charging cost", "meter", "energy delivered");
        boolean startFailure = containsAny(message,
                "start fail", "start failed", "failed to start", "remote start", "already_active", "already active");
        boolean sessionQuestion = containsAny(message,
                "session status", "active session", "stop charging", "remote stop");
        boolean stuckSession = containsAny(message, "stuck", "preparing");
        boolean liveness = containsAny(message,
                "online", "offline", "heartbeat", "connected", "connection");
        boolean availability = containsAny(message,
                "available", "availability", "free connector", "busy connector", "occupied", "charger status");

        if (containsAny(message, "payment", "wallet", "card", "balance", "billing", "refund", "hold", "auto top")
                || receiptOrCost) {
            diagnostics.add(PAYMENT);
        }
        if (startFailure || sessionQuestion || stuckSession || receiptOrCost || hasRequestedSession(context)) {
            diagnostics.add(SESSION);
        }
        if (availability || startFailure || containsAny(message, "find charger", "nearby charger", "another charger")) {
            diagnostics.add(CHARGER);
        }
        if (liveness || startFailure || stuckSession) {
            diagnostics.add(OCPP_CONNECTION);
        }
        if (isAdministrativeAudience(context)
                && containsAny(message, "ocpp history", "message history", "raw ocpp", "recent ocpp actions")) {
            diagnostics.add(OCPP_HISTORY);
        }
        return Set.copyOf(diagnostics);
    }

    private static boolean hasRequestedSession(ContextPayload context) {
        return context != null && context.sessionId() != null && !context.sessionId().isBlank();
    }

    static boolean investigatesSession(String message, ContextPayload context) {
        if (!isAdministrativeAudience(context) || !hasRequestedSession(context)) return false;
        String mode = context.attributes() == null ? "" : context.attributes().get("responseMode");
        if ("KNOWLEDGE".equals(mode) || "CHANGE_PRECHECK".equals(mode)) return false;
        return "SELECTED_RECORD".equals(mode) || containsAny(normalize(message),
                "diagnos", "investigat", "why", "stuck", "meter", "bill", "authorization", "subscription", "timeline");
    }

    private static boolean isAdministrativeAudience(ContextPayload context) {
        if (context == null || context.audience() == null) {
            return false;
        }
        String audience = normalize(context.audience());
        return audience.contains("admin") || audience.contains("support") || audience.contains("csr");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
