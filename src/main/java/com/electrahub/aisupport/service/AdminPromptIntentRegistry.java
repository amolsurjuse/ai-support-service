package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** Reviewed prompt contracts. Browser intent selects a capability, never authority or evidence. */
@Component
public final class AdminPromptIntentRegistry {
    private static final Map<String, Contract> LIVE = Map.ofEntries(
            Map.entry("dashboard.attention", new Contract("dashboard", "LIVE_SUMMARY")),
            Map.entry("dashboard.revenue", new Contract("dashboard", "LIVE_SUMMARY")),
            Map.entry("dashboard.success-rate", new Contract("dashboard", "LIVE_SUMMARY")),
            Map.entry("dashboard.billing-summary", new Contract("dashboard", "LIVE_SUMMARY")),
            Map.entry("dashboard.active-sessions", new Contract("dashboard", "LIVE_SUMMARY")),
            Map.entry("sessions.overview", new Contract("charging-sessions", "SELECTED_RECORD")),
            Map.entry("sessions.stuck", new Contract("charging-sessions", "SELECTED_RECORD")),
            Map.entry("sessions.meter-cost", new Contract("charging-sessions", "SELECTED_RECORD")),
            Map.entry("sessions.authorization", new Contract("charging-sessions", "SELECTED_RECORD")));
    private final Map<String, Guide> guides;
    private final Map<String, List<Guide>> screens;

    public AdminPromptIntentRegistry() {
        try (var input = getClass().getResourceAsStream("/knowledge/admin-screen-guides.json")) {
            if (input == null) throw new IllegalStateException("Reviewed admin prompt guides are missing");
            JsonNode root = JsonMapper.builder().build().readTree(input);
            if (!root.isObject() || root.isEmpty()) throw new IllegalStateException("Invalid admin prompt guide catalog");
            Map<String, Guide> parsed = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                JsonNode item = entry.getValue();
                String id = entry.getKey(), screen = item.path("screen").asText(), mode = item.path("responseMode").asText();
                String answer = item.path("answer").asText();
                if (!id.matches("[a-z0-9][a-z0-9.-]{1,100}") || !screen.matches("[a-z][a-z0-9-]{1,60}")
                        || !Set.of("KNOWLEDGE", "CHANGE_PRECHECK").contains(mode)
                        || answer.isBlank() || answer.length() > 4000 || LIVE.containsKey(id))
                    throw new IllegalStateException("Invalid admin prompt contract: " + id);
                parsed.put(id, new Guide(id, screen, mode, answer));
            });
            this.guides = Collections.unmodifiableMap(parsed);
            Map<String, List<Guide>> byScreen = new LinkedHashMap<>();
            parsed.values().forEach(guide -> byScreen.computeIfAbsent(guide.screen(), ignored -> new ArrayList<>()).add(guide));
            byScreen.replaceAll((screen, values) -> List.copyOf(values));
            this.screens = Map.copyOf(byScreen);
        } catch (Exception failure) { throw new IllegalStateException("Cannot load reviewed admin prompt guide catalog", failure); }
    }

    Optional<DiagnosticAnswerService.DiagnosticAnswer> answer(String message, ContextPayload context) {
        if (context == null || context.driverAudience()) return Optional.empty();
        String screen = Objects.toString(context.screen(), ""), intent = attribute(context, "promptIntent"), mode = attribute(context, "responseMode");
        if (!intent.isBlank()) {
            Guide guide = guides.get(intent);
            Contract contract = guide == null ? LIVE.get(intent) : new Contract(guide.screen(), guide.responseMode());
            if (contract == null || !contract.screen().equals(screen) || !contract.responseMode().equals(mode))
                return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer("admin.prompt.unsupported",
                        "This suggested question does not match the current page or response type. Refresh the page and choose one of its suggested questions. No records were queried.", ""));
            if (guide != null) return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer("admin.guide." + intent, guide.answer(), ""));
            if ("charging-sessions".equals(contract.screen()) && !hasConsistentSessionSelection(context))
                return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer("admin.selected-record.required",
                        "Select a valid charging session using Diagnose with Sparky on its row, then retry. The selected session and record identifiers must agree. No records were queried.", ""));
            return Optional.empty(); // The existing authorized dashboard/session collector executes the reviewed live capability.
        }
        // Unwired screens cannot answer a live question by borrowing a keyword-matched
        // driver or other-screen tool. Be explicit about their current capability.
        if (screens.containsKey(screen) && !Set.of("dashboard", "charging-sessions").contains(screen)) {
            List<Guide> available = screens.get(screen);
            String label = screen.replace('-', ' ');
            return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer("admin.guide.screen",
                    "I can explain " + label + " and the checks to make before changes. Live records and selected-row analysis are not connected on this page.\n\n"
                            + "Choose a suggested question for a specific explanation. " + available.getFirst().answer(), ""));
        }
        return Optional.empty();
    }

    private static boolean hasConsistentSessionSelection(ContextPayload context) {
        String session = context.sessionId();
        try {
            if (session == null || !UUID.fromString(session).toString().equalsIgnoreCase(session)) return false;
            return context.resourceId() == null || context.resourceId().isBlank()
                    || session.equalsIgnoreCase(context.resourceId());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    Map<String, Guide> guides() { return guides; }
    static String attribute(ContextPayload context, String key) {
        return context == null || context.attributes() == null ? "" : Objects.toString(context.attributes().get(key), "");
    }
    record Guide(String id, String screen, String responseMode, String answer) { }
    private record Contract(String screen, String responseMode) { }
}
