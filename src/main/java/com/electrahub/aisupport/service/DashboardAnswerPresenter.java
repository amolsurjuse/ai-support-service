package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import java.util.*;

/** Answers one dashboard question using only its scoped, server-collected evidence. */
final class DashboardAnswerPresenter {
    enum View { OVERVIEW, ATTENTION, REVENUE, SUCCESS_RATE, BILLING, ACTIVE_SESSIONS }
    private DashboardAnswerPresenter() { }
    static View view(ContextPayload context) {
        return switch (AdminPromptIntentRegistry.attribute(context, "promptIntent")) {
            case "dashboard.attention" -> View.ATTENTION;
            case "dashboard.revenue" -> View.REVENUE;
            case "dashboard.success-rate" -> View.SUCCESS_RATE;
            case "dashboard.billing-summary" -> View.BILLING;
            case "dashboard.active-sessions" -> View.ACTIVE_SESSIONS;
            default -> View.OVERVIEW;
        };
    }
    static String present(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot evidence) {
        View view = view(context);
        List<String> facts = evidence.facts().stream().filter(fact -> relevant(fact, view)).limit(7).toList();
        String title = switch (view) {
            case ATTENTION -> "Charging outcomes and current sessions to review";
            case REVENUE -> "Revenue for the selected period";
            case SUCCESS_RATE -> "Charging success for the selected period";
            case BILLING -> "Billing activity for the selected period";
            case ACTIVE_SESSIONS -> "Current active-session sample";
            default -> "Scoped dashboard checks";
        };
        StringBuilder answer = new StringBuilder(facts.isEmpty()
                ? "I could not verify " + title.toLowerCase(Locale.ROOT) + ". No specific incident is confirmed."
                : title + ":");
        facts.forEach(fact -> answer.append("\n\n").append(fact));
        if (!facts.isEmpty() && view == View.REVENUE)
            answer.append("\n\nThis is recorded billing revenue for the selected currency and receipt/event dates. It does not prove bank capture or settlement.");
        if (!evidence.gaps().isEmpty()) {
            answer.append("\n\nLimits and next checks:");
            evidence.gaps().stream().limit(4).forEach(gap -> answer.append("\n- ").append(gap));
        }
        evidence.facts().stream().filter(fact -> fact.startsWith("Dashboard evidence retrieved at ")).findFirst()
                .ifPresent(fact -> answer.append("\n\n").append(fact));
        return answer.toString();
    }
    private static boolean relevant(String fact, View view) {
        if (fact.startsWith("Dashboard evidence retrieved at ")) return false;
        boolean outcome = fact.startsWith("Selected period:") || fact.startsWith("No eligible completed/failed sessions");
        boolean billing = fact.startsWith("Billing for the selected period") || fact.startsWith("Recorded revenue:");
        boolean current = fact.startsWith("Current session snapshot") || fact.startsWith("Current session to inspect:");
        return switch (view) {
            case ATTENTION -> outcome || current;
            case REVENUE, BILLING -> billing;
            case SUCCESS_RATE -> outcome;
            case ACTIVE_SESSIONS -> current;
            case OVERVIEW -> outcome || billing || current;
        };
    }
}
