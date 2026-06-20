package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import org.springframework.stereotype.Service;

@Service
public class DiagnosticAnswerService {
    private final AiSupportProperties properties;
    private final PiiRedactor redactor;

    DiagnosticAnswerService(AiSupportProperties properties, PiiRedactor redactor) {
        this.properties = properties;
        this.redactor = redactor;
    }

    public DiagnosticAnswer answer(String userMessage, ContextPayload context) {
        String message = redactor.redact(userMessage).toLowerCase();
        ContextPayload safeContext = context == null ? new ContextPayload(null, null, null, null, null, null, null, "driver") : context;

        if (message.contains("503") || message.contains("unavailable") || message.contains("temporarily")) {
            return chargingUnavailable(safeContext);
        }
        if (message.contains("already_active") || message.contains("already active") || message.contains("in progress")) {
            return alreadyActive(safeContext);
        }
        if (message.contains("stuck") || message.contains("preparing")) {
            return stuckPreparing(safeContext);
        }
        if (message.contains("online") || message.contains("offline") || message.contains("heartbeat")) {
            return heartbeat(safeContext);
        }

        return generalChargingHelp(safeContext);
    }

    private DiagnosticAnswer chargingUnavailable(ContextPayload context) {
        return new DiagnosticAnswer(
                "diagnose_charging_start",
                """
                        I can help check that. A 503 during start usually means the charger command could not be completed at that moment.

                        Most likely causes:
                        - the charger was not connected to ElectraHub
                        - the connector was not actually available
                        - another session was still preparing or finishing

                        Try refreshing the charger screen. If the charger still shows unavailable, pick another connector or contact support at %s.
                        """.formatted(properties.supportEmail()).trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer alreadyActive(ContextPayload context) {
        return new DiagnosticAnswer(
                "diagnose_active_connector",
                """
                        That response means ElectraHub sees a session already in progress for this connector.

                        If this is your session, stay on the charging screen and wait for live updates. If the charger is not physically charging after about 30 seconds, stop and retry from a different available connector.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer stuckPreparing(ContextPayload context) {
        return new DiagnosticAnswer(
                "diagnose_session_state",
                """
                        A session stuck in Preparing means the app received the start response, but ElectraHub is still waiting for the charger to confirm charging has begun.

                        Keep the app open for a few seconds. If power and cost do not start moving, the charger may be offline, busy, or not sending meter updates.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer heartbeat(ContextPayload context) {
        return new DiagnosticAnswer(
                "check_charger_liveness",
                """
                        Charger availability depends on recent heartbeat messages from the charger.

                        If the charger stops sending heartbeat events, ElectraHub marks it unavailable so drivers do not start sessions on a charger that cannot receive commands.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer generalChargingHelp(ContextPayload context) {
        return new DiagnosticAnswer(
                "driver_support_context",
                """
                        I can help with charging start failures, charger availability, session status, payment state, and live charging updates.

                        For the fastest help, ask something like "why did start fail?", "is this charger online?", or "why is my session stuck?"
                        """.trim(),
                contextSummary(context)
        );
    }

    private String contextSummary(ContextPayload context) {
        if (context == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        append(builder, "charger", context.chargerId());
        append(builder, "connector", context.connectorId());
        append(builder, "location", context.locationId());
        append(builder, "session", context.sessionId());
        return builder.toString();
    }

    private void append(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" | ");
        }
        builder.append(label).append(": ").append(value);
    }

    public record DiagnosticAnswer(String toolName, String text, String contextSummary) {
    }
}
