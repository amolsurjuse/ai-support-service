package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DashboardAnswerPresenterTest {
    private final BackendDiagnosticsClient.DiagnosticsSnapshot evidence = new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(
            "Dashboard evidence retrieved at 2026-10-06T00:00:00Z; selected period=2026-10-01T00:00:00Z to 2026-10-06T00:00:00Z.",
            "Selected period: 2 of 10 eligible sessions failed (80% charging success); 8 completed/billed.",
            "Billing for the selected period (EUR): 8 sessions, 12 kWh, 24 EUR recorded revenue.",
            "Current session snapshot at 2026-10-06T00:00:00Z: 1 preparing; sample counts, not fleet totals.",
            "Current session to inspect: 11111111-2222-4333-8444-555555555555; status=PREPARING."), List.of());

    @Test void dashboardQuestionsProduceDistinctAnswersContainingOnlyRelevantEvidence() {
        var results = new HashSet<String>();
        for (String intent : List.of("dashboard.attention", "dashboard.revenue", "dashboard.success-rate", "dashboard.billing-summary", "dashboard.active-sessions"))
            results.add(DashboardAnswerPresenter.present(context(intent), evidence));
        assertThat(results).hasSize(5);
        assertThat(DashboardAnswerPresenter.present(context("dashboard.success-rate"), evidence))
                .contains("80% charging success").doesNotContain("24 EUR", "Current session to inspect");
        assertThat(DashboardAnswerPresenter.present(context("dashboard.active-sessions"), evidence))
                .contains("sample counts", "PREPARING").doesNotContain("24 EUR", "80% charging success");
        assertThat(DashboardAnswerPresenter.present(context("dashboard.revenue"), evidence))
                .contains("24 EUR", "does not prove bank capture").doesNotContain("80% charging success", "Current session to inspect");
    }

    @Test void zeroEligibleOutcomesAndUnavailableFactsNeverBecomeHealthyFleetClaims() {
        var empty = new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of("No eligible completed/failed sessions in the selected period, so a charging success rate cannot be established."), List.of());
        assertThat(DashboardAnswerPresenter.present(context("dashboard.success-rate"), empty)).contains("cannot be established").doesNotContain("0%", "healthy");
        var denied = new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of("Period billing overview is not permitted for your current account or scope."));
        assertThat(DashboardAnswerPresenter.present(context("dashboard.revenue"), denied))
                .contains("could not verify", "not permitted").doesNotContain("0 EUR", "no failures", "healthy");
    }
    private ContextPayload context(String intent) {
        return new ContextPayload("dashboard", "dashboard", null, null, null, null, null, "admin", Map.of("promptIntent", intent, "responseMode", "LIVE_SUMMARY"));
    }
}
