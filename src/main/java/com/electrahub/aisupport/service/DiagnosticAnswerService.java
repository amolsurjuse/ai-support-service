package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DiagnosticAnswerService {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticAnswerService.class);
    private final AiSupportProperties properties;
    private final PiiRedactor redactor;
    private final BackendDiagnosticsClient diagnosticsClient;
    private final LlmClient llmClient;

    DiagnosticAnswerService(AiSupportProperties properties,
                            PiiRedactor redactor,
                            BackendDiagnosticsClient diagnosticsClient,
                            LlmClient llmClient) {
        this.properties = properties;
        this.redactor = redactor;
        this.diagnosticsClient = diagnosticsClient;
        this.llmClient = llmClient;
    }

    public DiagnosticAnswer answer(String userMessage, ContextPayload context, String authorization) {
        String message = redactor.redact(userMessage).toLowerCase();
        ContextPayload safeContext = context == null ? new ContextPayload(null, null, null, null, null, null, null, "driver") : context;
        BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics = diagnosticsClient.collect(safeContext, authorization);

        DiagnosticAnswer fallback;
        if (message.contains("503") || message.contains("unavailable") || message.contains("temporarily")) {
            fallback = chargingUnavailable(safeContext, diagnostics);
        } else if (message.contains("already_active") || message.contains("already active") || message.contains("in progress")) {
            fallback = alreadyActive(safeContext, diagnostics);
        } else if (message.contains("stuck") || message.contains("preparing")) {
            fallback = stuckPreparing(safeContext, diagnostics);
        } else if (message.contains("online") || message.contains("offline") || message.contains("heartbeat")) {
            fallback = heartbeat(safeContext, diagnostics);
        } else {
            fallback = generalChargingHelp(safeContext, diagnostics);
        }

        return llmAnswerOrFallback(userMessage, safeContext, diagnostics, fallback);
    }

    private DiagnosticAnswer llmAnswerOrFallback(String userMessage,
                                                 ContextPayload context,
                                                 BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics,
                                                 DiagnosticAnswer fallback) {
        if (!llmClient.available()) {
            log.info("Sparky using deterministic fallback reason=llm_unavailable tool={} contextSummaryPresent={}",
                    fallback.toolName(), fallback.contextSummary() != null && !fallback.contextSummary().isBlank());
            return fallback;
        }
        LlmClient.LlmCompletion completion = llmClient.complete(new LlmClient.LlmPrompt(
                redactor.redact(userMessage),
                context,
                fallback,
                diagnostics
        ));
        if (!completion.ok() || completion.answer().isBlank()) {
            log.warn("Sparky using deterministic fallback reason=llm_completion_failed provider={} model={} tool={} error={}",
                    completion.provider(), completion.model(), fallback.toolName(), completion.error());
            return fallback;
        }
        log.info("Sparky using LLM answer provider={} model={} tool={} answerChars={}",
                completion.provider(), completion.model(), fallback.toolName(), completion.answer().length());
        return new DiagnosticAnswer(fallback.toolName(), completion.answer(), fallback.contextSummary());
    }

    public String renderForClient(DiagnosticAnswer answer) {
        if (answer == null) {
            return "";
        }
        if (answer.contextSummary() == null || answer.contextSummary().isBlank()) {
            return answer.text();
        }
        return "I checked " + answer.contextSummary() + ".\n\n" + answer.text();
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
