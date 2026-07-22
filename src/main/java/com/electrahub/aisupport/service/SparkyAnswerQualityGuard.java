package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps the deterministic diagnostic answer as Sparky's source of truth while
 * allowing the language model to make that answer easier to read.
 */
final class SparkyAnswerQualityGuard {
    private static final int MINIMUM_ANSWER_LENGTH = 44;
    private static final int MAXIMUM_ANSWER_LENGTH = 2_400;
    private static final Set<String> DOMAIN_TERMS = Set.of(
            "charger", "connector", "session", "charging", "available", "unavailable", "offline", "online",
            "idle", "unplug", "receipt", "simulator", "security", "payment", "wallet", "card", "credit",
            "tariff", "pricing", "subscription", "discount", "revenue", "notification", "ocpp", "rfid",
            "pnc", "certificate", "top-up", "topup", "balance", "remote", "stop");
    private static final Pattern ELECTRAHUB_IDENTIFIER = Pattern.compile("\\b(?:EH-[A-Z0-9-]+|CON-[A-Z0-9-]+)\\b");

    Evaluation evaluate(String candidate,
                        DiagnosticAnswerService.DiagnosticAnswer fallback,
                        String userMessage,
                        ContextPayload context) {
        String answer = stripReasoning(candidate);
        if (answer.isBlank()) {
            return Evaluation.rejected("blank");
        }
        if (answer.length() < MINIMUM_ANSWER_LENGTH) {
            return Evaluation.rejected("too_short");
        }
        if (answer.length() > MAXIMUM_ANSWER_LENGTH) {
            return Evaluation.rejected("too_long");
        }

        String normalized = answer.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "system prompt", "response rules", "project knowledge:", "backend facts:",
                "authoritative draft", "deterministic fallback", "ignore previous", "developer message",
                "<think", "<analysis", "as an ai language model")) {
            return Evaluation.rejected("prompt_or_reasoning_leak");
        }
        if (isDriver(context) && containsAny(normalized,
                "session-service", "ocpp-service", "payment-service", "subscription-service", "stack trace", "sql")) {
            return Evaluation.rejected("driver_internal_detail");
        }
        if (!preservesAuthoritativeIntent(answer, fallback)) {
            return Evaluation.rejected("authoritative_intent_not_preserved");
        }
        if (!hasDomainGrounding(answer, fallback == null ? "" : fallback.text(), userMessage)) {
            return Evaluation.rejected("missing_domain_grounding");
        }
        return Evaluation.accepted(answer);
    }

    /**
     * The deterministic answer is the business-rule source of truth. These focused checks cover
     * the flows where a polished but altered answer could create a financial or operational risk.
     */
    private static boolean preservesAuthoritativeIntent(String answer,
                                                        DiagnosticAnswerService.DiagnosticAnswer fallback) {
        if (fallback == null) {
            return true;
        }
        String normalized = answer.toLowerCase(Locale.ROOT);
        String fallbackText = fallback.text() == null ? "" : fallback.text().toLowerCase(Locale.ROOT);
        return switch (fallback.toolName()) {
            case "check_charger_availability" -> preservesAvailabilityOutcome(normalized, fallbackText);
            case "diagnose_idle_remote_stop" -> containsAll(normalized, "idle", "unplug");
            case "prepare_remote_stop" -> containsAny(normalized, "remote stop", "remotely stop")
                    && containsAny(normalized, "idle", "unplug", "receipt");
            case "diagnose_simulator_secure_unplug" -> containsAny(normalized, "unplug")
                    && containsAny(normalized, "code", "security");
            case "manage_charger_status" -> containsAny(normalized, "charger", "connector")
                    && containsAny(normalized, "status", "heartbeat", "inoperative");
            case "explain_explicit_available_status" -> containsAll(normalized, "available", "status")
                    && containsAny(normalized, "transaction id", "transaction");
            case "explain_card_present_admin_payment" -> containsAll(normalized, "credit", "card", "mask");
            case "explain_receipt_lookup" -> containsAny(normalized, "session", "history", "receipt")
                    && containsAny(normalized, "select", "open", "specific");
            case "diagnose_past_session" -> preservesPastSessionIntent(normalized, fallbackText);
            case "diagnose_charging_start" -> preservesMissingContextStartFailure(normalized, fallbackText);
            case "explain_spend_analytics_gap" ->
                    containsAny(normalized, "cannot", "unavailable", "not available", "need")
                            && containsAny(normalized, "report", "aggregation", "history", "dashboard");
            case "explain_usage_analytics_gap" -> containsAny(normalized, "cannot", "unavailable", "not available", "need")
                    && containsAny(normalized, "report", "aggregation", "history", "dashboard")
                    && !containsAny(normalized, "until you have completed sessions", "no completed sessions", "session history", "completed trips", "contact support");
            case "explain_trip_data_unavailable" -> containsAll(normalized, "trip", "telemetry");
            case "explain_pricing_context_needed" -> containsAny(normalized, "tariff", "pricing")
                    && containsAny(normalized, "select", "specific", "context");
            case "authorize_rfid" -> containsAny(normalized, "reject", "not authorized", "cannot")
                    && containsAny(normalized, "session", "transaction", "charging");
            case "authorize_plug_and_charge" -> containsAny(normalized, "certificate", "emaid")
                    && containsAny(normalized, "reject", "not start", "cannot");
            case "explain_payment_authorization" -> containsAny(normalized, "hold", "authorization")
                    && containsAny(normalized, "void", "reverse", "release")
                    && normalized.contains("promptly")
                    && !containsAny(normalized, "hold will not be applied", "once the session is confirmed");
            case "explain_real_time_cost" -> containsAny(normalized, "backend", "receipt")
                    && containsAny(normalized, "cost", "billing", "idle");
            case "explain_pricing_caps" -> containsAny(normalized, "backend", "receipt")
                    && containsAll(normalized, "idle", "cap");
            case "explain_receipt_cost_consistency" -> containsAll(normalized, "receipt", "active")
                    && containsAny(normalized, "backend", "final", "settlement");
            case "explain_subscription_discount" -> containsAll(normalized, "subscription", "discount")
                    && containsAny(normalized, "quota", "scope", "receipt");
            case "explain_subscription_quota", "explain_subscription_exhaustion" -> containsAll(normalized,
                    "subscription", "quota");
            case "explain_dashboard_attention" -> containsAll(normalized, "dashboard", "session")
                    && containsAny(normalized, "charger", "payment", "notification");
            case "explain_charging_success_monitoring" -> containsAny(normalized, "charging success", "success rate", "csr")
                    && containsAny(normalized, "session", "failure", "eligible");
            case "explain_rbac_scope" -> containsAny(normalized, "access", "scope")
                    && containsAny(normalized, "location", "network", "enterprise");
            case "explain_charging_notifications" -> containsAny(normalized, "notification", "alert")
                    && containsAny(normalized, "backend", "session", "state");
            case "explain_notification_generation" -> containsAll(normalized, "notification", "backend")
                    && containsAny(normalized, "event", "session", "deduplicat");
            case "explain_push_delivery_failure" -> containsAll(normalized, "push", "notification")
                    && containsAny(normalized, "retry", "delivery", "firebase");
            case "find_charger_alternatives" -> preservesImportantIdentifiers(answer, fallback.text());
            default -> true;
        };
    }

    private static boolean preservesAvailabilityOutcome(String answer, String fallback) {
        if (fallback.contains("not available")) {
            return answer.contains("not available") || answer.contains("busy") || answer.contains("in use");
        }
        if (fallback.contains("appears available")) {
            return answer.contains("available") && !answer.contains("not available");
        }
        return true;
    }

    private static boolean preservesMissingContextStartFailure(String answer, String fallback) {
        if (!fallback.contains("do not have a selected charger")) {
            return true;
        }
        return containsAny(answer, "cannot confirm", "do not have", "no selected", "may", "can")
                && !containsAny(answer, "because no charger or connector is currently available",
                "no charger or connector is currently available");
    }

    private static boolean preservesPastSessionIntent(String answer, String fallback) {
        if (fallback.contains("i can investigate the selected charging session")) {
            return containsAll(answer, "selected", "session")
                    && !containsAny(answer, "could have failed", "may have failed", "likely failed because");
        }
        return containsAny(answer, "cannot", "need", "select")
                && containsAny(answer, "session", "history")
                && !containsAny(answer, "failed because there", "failed because no", "failure is because");
    }

    private static boolean preservesImportantIdentifiers(String answer, String fallback) {
        String uppercaseAnswer = answer.toUpperCase(Locale.ROOT);
        return ELECTRAHUB_IDENTIFIER.matcher(fallback == null ? "" : fallback.toUpperCase(Locale.ROOT))
                .results()
                .map(match -> match.group())
                .allMatch(uppercaseAnswer::contains);
    }

    private static boolean containsAll(String value, String... needles) {
        for (String needle : needles) {
            if (!value.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    private static String stripReasoning(String candidate) {
        if (candidate == null) {
            return "";
        }
        String result = candidate.trim();
        int endThinking = result.lastIndexOf("</think>");
        if (endThinking >= 0) {
            result = result.substring(endThinking + "</think>".length()).trim();
        }
        int endAnalysis = result.lastIndexOf("</analysis>");
        if (endAnalysis >= 0) {
            result = result.substring(endAnalysis + "</analysis>".length()).trim();
        }
        return result;
    }

    private static boolean hasDomainGrounding(String answer, String fallback, String userMessage) {
        String answerLower = answer.toLowerCase(Locale.ROOT);
        String source = (fallback + " " + nullToBlank(userMessage)).toLowerCase(Locale.ROOT);
        int sourceTerms = countTerms(source);
        int answerTerms = countTerms(answerLower);
        return sourceTerms == 0 ? answerTerms >= 1 : answerTerms >= Math.min(2, sourceTerms);
    }

    private static int countTerms(String value) {
        int count = 0;
        for (String term : DOMAIN_TERMS) {
            if (value.contains(term)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isDriver(ContextPayload context) {
        if (context == null || context.audience() == null || context.audience().isBlank()) {
            return true;
        }
        String audience = context.audience().toLowerCase(Locale.ROOT);
        return audience.contains("driver") || audience.contains("owner");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    record Evaluation(boolean accepted, String answer, String reason) {
        static Evaluation accepted(String answer) {
            return new Evaluation(true, answer, "accepted");
        }

        static Evaluation rejected(String reason) {
            return new Evaluation(false, "", reason);
        }
    }
}
