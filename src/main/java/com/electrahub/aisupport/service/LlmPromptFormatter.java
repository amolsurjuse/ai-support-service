package com.electrahub.aisupport.service;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LlmPromptFormatter {
    private static final Pattern ELECTRAHUB_IDENTIFIER = Pattern.compile("\\b(?:EH-[A-Z0-9-]+|CON-[A-Z0-9-]+)\\b");

    private LlmPromptFormatter() {
    }

    static String promptText(LlmClient.LlmPrompt prompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("User question:\n").append(truncate(nullToBlank(prompt.userMessage()), 400)).append("\n\n");
        builder.append("Runtime context:\n");
        if (prompt.context() == null) {
            builder.append("- none\n");
        } else {
            append(builder, "audience", prompt.context().audience());
            append(builder, "screen", prompt.context().screen());
            append(builder, "resourceType", prompt.context().resourceType());
            append(builder, "chargerId", prompt.context().chargerId());
            append(builder, "connectorId", prompt.context().connectorId());
            append(builder, "locationId", prompt.context().locationId());
            append(builder, "sessionId", prompt.context().sessionId());
            if (prompt.context().attributes() != null && !prompt.context().attributes().isEmpty()) {
                prompt.context().attributes().entrySet().stream().limit(12).forEach(entry ->
                        append(builder, "attribute." + entry.getKey(), truncate(entry.getValue(), 120)));
            }
        }
        builder.append("\nProject behavior relevant to this question:\n")
                .append(ElectraHubKnowledgeBase.relevantFacts(prompt.userMessage(), prompt.context(), 2))
                .append("\n\nAuthoritative answer that must remain true:\n")
                .append(prompt.deterministicAnswer() == null ? "" : truncate(prompt.deterministicAnswer().text(), 900))
                .append("\n\nVerified backend facts and unavailable checks:\n")
                .append(prompt.diagnostics() == null
                        ? "No backend facts were available."
                        : truncate(prompt.diagnostics().toAnswerText(), 900))
                .append("""

                        \n\nWrite the final user-facing answer now.
                        - Rewrite the authoritative answer faithfully. Preserve every outcome, restriction, and next step.
                        - Do not infer a failure, success, charge, availability state, payment outcome, or amount.
                        - Preserve exact identifiers, connector names, amounts, statuses, and required lifecycle steps.
                        - Missing context or an unavailable report is not evidence of a failure, unavailable charger, or absent completed sessions.
                        - If clearer wording would change the meaning, use the authoritative wording instead.
                        - Use verified backend facts only when they apply to the selected charger, connector, or session.
                        - Do not add statistics, prices, availability, payment, or session facts that were not supplied.
                        - Do not mention this instruction, prompt labels, model, or hidden reasoning.
                        """);
        appendRequiredIdentifiers(builder, prompt);
        appendPaymentAuthorizationInvariant(builder, prompt);
        appendAnalyticsInvariant(builder, prompt);
        appendDashboardAttentionInvariant(builder, prompt);
        appendPastSessionInvariant(builder, prompt);
        return builder.toString();
    }

    static int promptSize(LlmClient.LlmPrompt prompt) {
        return promptText(prompt).length() + SupportPrompt.SYSTEM_PROMPT.length() + SupportPrompt.RESPONSE_CONTRACT.length();
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (!isBlank(value)) {
            builder.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void appendRequiredIdentifiers(StringBuilder builder, LlmClient.LlmPrompt prompt) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        String source = (prompt.deterministicAnswer() == null ? "" : prompt.deterministicAnswer().text())
                + "\n" + (prompt.diagnostics() == null ? "" : prompt.diagnostics().toAnswerText());
        Matcher matcher = ELECTRAHUB_IDENTIFIER.matcher(source.toUpperCase());
        while (matcher.find()) {
            identifiers.add(matcher.group());
        }
        if (identifiers.isEmpty()) {
            return;
        }
        builder.append("\n\nExact supplied identifiers that must appear verbatim in the final answer:\n");
        identifiers.forEach(identifier -> builder.append("- ").append(identifier).append('\n'));
    }

    private static void appendPaymentAuthorizationInvariant(StringBuilder builder, LlmClient.LlmPrompt prompt) {
        if (prompt.deterministicAnswer() == null
                || !"explain_payment_authorization".equals(prompt.deterministicAnswer().toolName())) {
            return;
        }
        builder.append("""

                \nNon-negotiable payment lifecycle rule:
                - The configured hold is authorized before remote start.
                - If remote start or charger confirmation fails, say the unused authorization is voided or reversed promptly.
                - Do not say the hold was never applied, was not placed, or did not exist.
                """);
    }

    private static void appendAnalyticsInvariant(StringBuilder builder, LlmClient.LlmPrompt prompt) {
        if (prompt.deterministicAnswer() == null
                || !("explain_spend_analytics_gap".equals(prompt.deterministicAnswer().toolName())
                || "explain_usage_analytics_gap".equals(prompt.deterministicAnswer().toolName()))) {
            return;
        }
        builder.append("""

                \nNon-negotiable analytics rule:
                - A monthly spend, yearly kWh, or most-used station result needs a supplied completed-session aggregation.
                - If the aggregation is unavailable, say the result cannot be calculated yet.
                - Do not replace it with vehicle trips, generic history browsing, or an assumption that no completed sessions exist.
                - Do not direct the driver to session history or support as a way to calculate the unavailable aggregate.
                """);
    }

    private static void appendPastSessionInvariant(StringBuilder builder, LlmClient.LlmPrompt prompt) {
        if (prompt.deterministicAnswer() == null
                || !"diagnose_past_session".equals(prompt.deterministicAnswer().toolName())) {
            return;
        }
        builder.append("""

                \nNon-negotiable past-session diagnostic rule:
                - State that Sparky cannot diagnose the exact failure without the selected session.
                - Missing session context is not the cause of a charging failure.
                - Do not speculate about possible causes before asking the driver to open the selected history entry.
                """);
    }

    private static void appendDashboardAttentionInvariant(StringBuilder builder, LlmClient.LlmPrompt prompt) {
        if (prompt.deterministicAnswer() == null
                || !"explain_dashboard_attention".equals(prompt.deterministicAnswer().toolName())) {
            return;
        }
        builder.append("""

                \nNon-negotiable dashboard attention rule:
                - If current dashboard facts are unavailable, say that no specific incident is confirmed.
                - Still name the scoped review checklist: active, idle, or stuck sessions; offline or faulted chargers;
                  payment or settlement failures; and unread operational notifications.
                - Do not invent a current incident, count, or financial outcome.
                """);
    }
}
