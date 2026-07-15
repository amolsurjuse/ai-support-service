package com.electrahub.aisupport.service;

final class LlmPromptFormatter {
    private LlmPromptFormatter() {
    }

    static String promptText(LlmClient.LlmPrompt prompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("User message:\n").append(nullToBlank(prompt.userMessage())).append("\n\n");
        builder.append("Audience and screen context:\n");
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
                prompt.context().attributes().entrySet().stream().limit(24).forEach(entry ->
                        append(builder, "attribute." + entry.getKey(), truncate(entry.getValue(), 240)));
            }
        }
        builder.append("\nDeterministic Sparky fallback answer. Preserve its safety and do not contradict live facts:\n")
                .append(prompt.deterministicAnswer() == null ? "" : prompt.deterministicAnswer().text())
                .append("\n\nLive backend facts and gaps:\n")
                .append(prompt.diagnostics() == null ? "No backend facts were available." : prompt.diagnostics().toAnswerText())
                .append("\n\nWrite the final user-facing answer now.");
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
        return value.substring(0, maxLength);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
