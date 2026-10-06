package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardDiagnosticsClientTest {
    private static final String FROM = "2026-10-01T00:00:00Z", TO = "2026-10-06T00:00:00Z";
    private static final String SESSION = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER = "00000000-0000-4000-8000-000000000002";
    private static final IdentityContext ADMIN = new IdentityContext("tenant-a", "operator", Set.of("SYSTEM_ADMIN"), true);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TenantAiPolicyService policies = mock(TenantAiPolicyService.class);
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;
    private String base;
    private volatile String fault;
    private volatile String location = "LOC-A";

    @BeforeEach void start() throws Exception {
        when(policies.policyFor("tenant-a")).thenReturn(policy(Set.of("admin.dashboard.read")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            requests.add(new Request(path, query, exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().keySet().stream().filter(key -> key.toLowerCase().startsWith("x-electrahub")).toList(),
                    exchange.getRequestURI().getRawQuery()));
            try {
                if ("all-unavailable".equals(fault)) { exchange.sendResponseHeaders(503, -1); return; }
                if ("all-stalled".equals(fault)) {
                    exchange.sendResponseHeaders(200, 1024);
                    exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                    try { Thread.sleep(900); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return;
                }
                Object body;
                if (path.endsWith("charging-success-rate")) {
                    body = Map.of("from", "mismatched-date".equals(fault) ? "2025-01-01T00:00:00Z" : FROM, "to", TO,
                            "successfulSessions", "empty-outcome".equals(fault) ? 0 : 7, "failedSessions", "empty-outcome".equals(fault) ? 0 : 3,
                            "eligibleSessions", "empty-outcome".equals(fault) ? 0 : 10,
                            "excludedManualCancellations", 2, "excludedDriverPaymentFailures", 1, "chargingSuccessRate", "empty-outcome".equals(fault) ? 0 : 70);
                } else if (path.endsWith("overview")) {
                    if ("denied".equals(fault)) { exchange.sendResponseHeaders(403, -1); return; }
                    if ("unavailable".equals(fault)) { exchange.sendResponseHeaders(503, -1); return; }
                    if ("redirect".equals(fault)) {
                        exchange.getResponseHeaders().set("Location", base + "/unexpected");
                        exchange.sendResponseHeaders(302, -1); return;
                    }
                    if ("stalled".equals(fault)) {
                        exchange.sendResponseHeaders(200, 1024);
                        exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                        try { Thread.sleep(900); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    body = "incomplete".equals(fault) ? Map.of("currency", "EUR")
                            : Map.of("totalSessions", 7, "totalEnergyKwh", 12.5, "totalRevenue", 123.45,
                                    "currency", query.getOrDefault("currency", "ALL"), "lastUpdatedAt", "2026-10-05T18:00:00Z");
                } else if (path.endsWith("admin/search")) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    rows.add(row(SESSION, "ENT-A", "PREPARING"));
                    rows.add(row(OTHER, "ENT-AA", "FINISHING"));
                    if ("many".equals(fault)) for (int index = 3; index < 12; index++)
                        rows.add(row("00000000-0000-4000-8000-" + String.format("%012d", index), "ENT-A", "ACTIVE"));
                    body = Map.of("content", rows, "totalElements", 999999, "last", false);
                } else { exchange.sendResponseHeaders(404, -1); return; }
                byte[] bytes = "oversize".equals(fault) && path.endsWith("overview") ? new byte[220000] : mapper.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); serverExecutor.shutdownNow(); }

    @Test void fetchesBackendFactsWithOriginalBearerAndExactFilterParameters() {
        var attributes = attributes(); attributes.put("totalRevenue", "999999999");
        var report = client().collect(context(attributes), "Bearer current-operator", ADMIN);
        assertThat(requests).hasSize(3).allSatisfy(request -> {
            assertThat(request.bearer()).isEqualTo("Bearer current-operator");
            assertThat(request.identityHeaders()).isEmpty();
            assertThat(request.query()).containsEntry("enterpriseId", "ENT-A").containsEntry("networkId", "NET-A").containsEntry("locationId", "LOC-A");
        });
        assertThat(request("overview").query()).containsEntry("from", FROM).containsEntry("to", TO).containsEntry("currency", "EUR");
        assertThat(request("charging-success-rate").query()).doesNotContainKey("currency");
        assertThat(request("admin/search").query()).containsEntry("state", "ACTIVE").containsEntry("size", "25")
                .doesNotContainKeys("from", "to", "currency");
        assertThat(report.facts().toString()).contains("3 of 10 eligible sessions failed", "123.45 EUR recorded revenue", SESSION,
                        "PREPARING", "independent of selected date/currency", "sample counts, not fleet totals")
                .doesNotContain(OTHER, "999999", "private@example.test", "private-account");
        assertThat(report.gaps().toString()).contains("could not be checked", "first 25").doesNotContain("Country");
    }

    @Test void catalogIntentOnlyReadsTheEvidenceNeededForThatQuestion() {
        for (String intent : List.of("dashboard.revenue", "dashboard.billing-summary", "dashboard.success-rate", "dashboard.active-sessions")) {
            requests.clear();
            var report = client().collect(context(with("promptIntent", intent)), "Bearer current-operator", ADMIN);
            assertThat(requests).hasSize(1);
            String expected = intent.equals("dashboard.success-rate") ? "charging-success-rate"
                    : intent.equals("dashboard.active-sessions") ? "admin/search" : "overview";
            assertThat(requests.getFirst().path()).endsWith(expected);
            if (intent.equals("dashboard.revenue")) assertThat(report.facts().toString()).contains("Recorded revenue: 123.45 EUR").doesNotContain("3 of 10", SESSION);
            assertThat(report.gaps().toString()).doesNotContain("Charger health");
        }
        requests.clear();
        client().collect(context(with("promptIntent", "dashboard.attention")), "Bearer current-operator", ADMIN);
        assertThat(requests).hasSize(2).noneSatisfy(request -> assertThat(request.path()).endsWith("overview"));
    }

    @Test void rejectsUnreadyInvalidOrConflictingFiltersWithoutAnyRequest() {
        for (Map<String, String> attributes : List.of(Map.<String, String>of(), with("filtersReady", "false"),
                with("from", "2026-10-01"), with("from", "2027-10-01T00:00:00Z"),
                with("from", "2020-01-01T00:00:00Z"), with("filterEnterpriseId", "ENT%"), with("currency", "EUR&scope=all"))) {
            assertThat(client().collect(context(attributes), "Bearer current-operator", ADMIN).facts()).isEmpty();
        }
        var conflict = new ContextPayload("dashboard", "dashboard", null, null, null, "LOC-OTHER", null, "admin", attributes());
        assertThat(client().collect(conflict, "Bearer current-operator", ADMIN).facts()).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test void deniesDriversAnonymousAndDisallowedTenantPolicyBeforeTransport() {
        assertThatThrownBy(() -> client().collect(context(attributes()), "Bearer driver",
                new IdentityContext("tenant-a", "driver", Set.of("USER"), true))).hasMessageContaining("403");
        assertThatThrownBy(() -> client().collect(context(attributes()), "Bearer anonymous",
                new IdentityContext("tenant-a", "anon", Set.of("SYSTEM_ADMIN"), false))).hasMessageContaining("403");
        when(policies.policyFor("tenant-a")).thenReturn(policy(Set.of("admin.sessions.diagnose")));
        assertThat(client().collect(context(attributes()), "Bearer current-operator", ADMIN).gaps().toString()).contains("admin.dashboard.read");
        assertThat(requests).isEmpty();
    }

    @Test void readOnlyAdminUsesExistingReadScopeAndDoesNotGainSessionAnalysis() {
        var identity = new IdentityContext("tenant-a", "reader", Set.of("ADMIN_READ_ONLY"), true);
        assertThat(client().collect(context(attributes()), "Bearer reader", identity).facts()).isNotEmpty();
        assertThat(new AiToolAuthorizationService().canAnalyzeSupport(identity)).isFalse();
    }

    @Test void keepsSuccessfulSectionsWhenOneBackendFailsWithoutInventingZeros() {
        fault = "unavailable";
        var report = client().collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(report.facts().toString()).contains("3 of 10 eligible sessions failed", SESSION).doesNotContain("recorded revenue");
        assertThat(report.gaps().toString()).contains("Period billing overview unavailable");
    }

    @Test void rejectsOversizedRedirectedAndIncompleteResponsesWithoutFollowingRedirects() {
        for (String mode : List.of("oversize", "redirect", "incomplete")) {
            fault = mode;
            var report = client().collect(context(attributes()), "Bearer current-operator", ADMIN);
            assertThat(report.facts().toString()).contains("3 of 10 eligible sessions failed").doesNotContain("recorded revenue");
            assertThat(report.gaps().toString()).contains("Period billing overview unavailable");
        }
        assertThat(requests).hasSize(9).noneSatisfy(request -> assertThat(request.path()).endsWith("unexpected"));
    }

    @Test void wholeBodyDeadlineKeepsOtherEvidenceWhenResponseStallsAfterHeaders() {
        fault = "stalled";
        var client = new DashboardDiagnosticsClient(base, new AiToolAuthorizationService(), policies, mapper, Duration.ofMillis(300));
        long started = System.nanoTime();
        var report = client.collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(850));
        assertThat(report.facts().toString()).contains("3 of 10 eligible sessions failed");
        assertThat(report.gaps().toString()).contains("Period billing overview unavailable");
    }

    @Test void allFailedOrStalledReadsReturnOnlyExplicitGapsWithinTheSharedDeadline() {
        fault = "all-unavailable";
        var report = client().collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(report.facts()).isEmpty();
        assertThat(report.gaps().stream().filter(gap -> gap.contains("unavailable"))).hasSize(3);
        fault = "all-stalled";
        var client = new DashboardDiagnosticsClient(base, new AiToolAuthorizationService(), policies, mapper, Duration.ofMillis(300));
        long started = System.nanoTime();
        report = client.collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(850));
        assertThat(report.facts()).isEmpty();
        assertThat(report.gaps().stream().filter(gap -> gap.contains("unavailable"))).hasSize(3);
    }

    @Test void denialIsClearAndAnEmptyOutcomeDoesNotInventASuccessRate() {
        fault = "denied";
        var report = client().collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(report.gaps().toString()).contains("Period billing overview is not permitted");
        fault = "empty-outcome";
        report = client().collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(report.facts().toString()).contains("No eligible completed/failed sessions", "success rate cannot be established")
                .doesNotContain("0% charging success");
    }

    @Test void dateMismatchIsUnavailableAndCurrentSampleNeverIncludesMoreThanFiveIdentifiers() {
        fault = "mismatched-date";
        var report = client().collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(report.facts().toString()).doesNotContain("3 of 10 eligible sessions failed");
        assertThat(report.gaps().toString()).contains("Period charging outcome unavailable");
        fault = "many";
        report = client().collect(context(attributes()), "Bearer current-operator", ADMIN);
        assertThat(report.facts().stream().filter(fact -> fact.startsWith("Current session to inspect:"))).hasSize(5);
        assertThat(report.facts().toString()).doesNotContain("999999", OTHER);
    }

    @Test void noCurrencySelectionOmitsMonetaryTotalsRatherThanAddingCurrencies() {
        var report = client().collect(context(with("currency", "all")), "Bearer current-operator", ADMIN);
        assertThat(report.facts().toString()).contains("7 sessions", "12.5 kWh").doesNotContain("123.45", "recorded revenue");
        assertThat(report.gaps().toString()).contains("Monetary totals omitted");
        assertThat(request("overview").query()).doesNotContainKey("currency");
    }

    @Test void forwardsEmi3LocationIdentifiersAndMatchesTheirLiteralStars() {
        location = "US*EHB*LOC*SFO001";
        var report = client().collect(context(with("filterLocationId", location)), "Bearer current-operator", ADMIN);
        assertThat(report.facts().toString()).contains(SESSION).doesNotContain(OTHER);
        assertThat(requests).hasSize(3).allSatisfy(request -> {
            assertThat(request.query()).containsEntry("locationId", location);
            assertThat(request.rawQuery()).contains("US%2AEHB%2ALOC%2ASFO001");
        });
    }

    private DashboardDiagnosticsClient client() { return new DashboardDiagnosticsClient(base, new AiToolAuthorizationService(), policies, mapper); }
    private TenantAiPolicyService.TenantPolicy policy(Set<String> tools) { return new TenantAiPolicyService.TenantPolicy("tenant-a", true, 60, 5000, 1000000, List.of(), tools); }
    private Request request(String suffix) { return requests.stream().filter(request -> request.path().endsWith(suffix)).findFirst().orElseThrow(); }
    private ContextPayload context(Map<String, String> attributes) { return new ContextPayload("dashboard", "dashboard", null, null, null, null, null, "admin", attributes); }
    private Map<String, String> attributes() { return new LinkedHashMap<>(Map.of("filtersReady", "true", "from", FROM, "to", TO, "currency", "EUR",
            "filterEnterpriseId", "ENT-A", "filterNetworkId", "NET-A", "filterLocationId", "LOC-A")); }
    private Map<String, String> with(String key, String value) { var result = attributes(); result.put(key, value); return result; }
    private Map<String, Object> row(String id, String enterprise, String status) { return Map.of("id", id, "enterpriseId", enterprise,
            "networkId", "NET-A", "locationId", location, "status", status, "driverEmail", "private@example.test", "userAccountId", "private-account"); }
    private record Request(String path, Map<String, String> query, String bearer, List<String> identityHeaders, String rawQuery) { }
}
