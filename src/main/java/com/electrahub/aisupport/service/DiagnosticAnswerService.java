package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import org.springframework.stereotype.Service;

@Service
public class DiagnosticAnswerService {
    private final AiSupportProperties properties;
    private final PiiRedactor redactor;
    private final BackendDiagnosticsClient diagnosticsClient;

    DiagnosticAnswerService(AiSupportProperties properties, PiiRedactor redactor, BackendDiagnosticsClient diagnosticsClient) {
        this.properties = properties;
        this.redactor = redactor;
        this.diagnosticsClient = diagnosticsClient;
    }

    public DiagnosticAnswer answer(String userMessage, ContextPayload context, String authorization) {
        String message = redactor.redact(userMessage).toLowerCase();
        ContextPayload safeContext = context == null ? new ContextPayload(null, null, null, null, null, null, null, "driver") : context;
        BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics = diagnosticsClient.collect(safeContext, authorization);

        if (message.contains("503") || message.contains("unavailable") || message.contains("temporarily")) {
            return chargingUnavailable(safeContext, diagnostics);
        }
        if (message.contains("already_active") || message.contains("already active") || message.contains("in progress")) {
            return alreadyActive(safeContext, diagnostics);
        }
        if (message.contains("stuck") || message.contains("preparing")) {
            return stuckPreparing(safeContext, diagnostics);
        }
        if (message.contains("online") || message.contains("offline") || message.contains("heartbeat")) {
            return heartbeat(safeContext, diagnostics);
        }

        return generalChargingHelp(safeContext, diagnostics);
    }

    private DiagnosticAnswer chargingUnavailable(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        return new DiagnosticAnswer(
                "diagnose_charging_start",
                enrich("""
                        I can help check that. A 503 during start usually means the charger command could not be completed at that moment.

                        Most likely causes:
                        - the charger was not connected to ElectraHub
                        - the connector was not actually available
                        - another session was still preparing or finishing

                        Try refreshing the charger screen. If the charger still shows unavailable, pick another connector or contact support at %s.
                        """.formatted(properties.supportEmail()).trim(), diagnostics),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer alreadyActive(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        return new DiagnosticAnswer(
                "diagnose_active_connector",
                enrich("""
                        That response means ElectraHub sees a session already in progress for this connector.

                        If this is your session, stay on the charging screen and wait for live updates. If the charger is not physically charging after about 30 seconds, stop and retry from a different available connector.
                        """.trim(), diagnostics),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer stuckPreparing(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        return new DiagnosticAnswer(
                "diagnose_session_state",
                enrich("""
                        A session stuck in Preparing means the app received the start response, but ElectraHub is still waiting for the charger to confirm charging has begun.

                        Keep the app open for a few seconds. If power and cost do not start moving, the charger may be offline, busy, or not sending meter updates.
                        """.trim(), diagnostics),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer heartbeat(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        return new DiagnosticAnswer(
                "check_charger_liveness",
                enrich("""
                        Charger availability depends on recent heartbeat messages from the charger.

                        If the charger stops sending heartbeat events, ElectraHub marks it unavailable so drivers do not start sessions on a charger that cannot receive commands.
                        """.trim(), diagnostics),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer generalChargingHelp(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        return new DiagnosticAnswer(
                "driver_support_context",
                enrich("""
                        I can help with charging start failures, charger availability, session status, payment state, and live charging updates.

                        For the fastest help, ask something like "why did start fail?", "is this charger online?", or "why is my session stuck?"
                        """.trim(), diagnostics),
                contextSummary(context)
        );
    }

    private String enrich(String baseText, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        String liveFacts = diagnostics.toAnswerText();
        if (liveFacts.isBlank()) {
            return baseText;
        }
        return baseText + "\n\n" + liveFacts;
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
