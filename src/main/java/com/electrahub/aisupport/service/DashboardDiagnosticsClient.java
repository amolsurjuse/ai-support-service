package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Fixed, scoped dashboard reads. UI values select filters, never supply evidence or authority. */
@Component
public class DashboardDiagnosticsClient {
    private static final int MAX_BYTES = 192 * 1024;
    private static final List<String> ACTIVE_STATES = List.of("PENDING", "PREPARING", "FINISHING", "SUSPENDED", "ACTIVE");
    private final String gatewayUrl;
    private final AiToolAuthorizationService authorization;
    private final TenantAiPolicyService policies;
    private final ObjectMapper mapper;
    private final Duration budget;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    public DashboardDiagnosticsClient(
            @Value("${electrahub.ai-support.gateway-url:http://api-gateway:8090}") String gatewayUrl,
            AiToolAuthorizationService authorization, TenantAiPolicyService policies, ObjectMapper mapper) {
        this(gatewayUrl, authorization, policies, mapper, Duration.ofMillis(6500));
    }

    DashboardDiagnosticsClient(String gatewayUrl, AiToolAuthorizationService authorization,
                               TenantAiPolicyService policies, ObjectMapper mapper, Duration budget) {
        URI base = URI.create(gatewayUrl);
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Invalid dashboard gateway URL");
        this.gatewayUrl = gatewayUrl.replaceAll("/+$", "");
        this.authorization = authorization;
        this.policies = policies;
        this.mapper = mapper;
        this.budget = budget;
    }

    public BackendDiagnosticsClient.DiagnosticsSnapshot collect(ContextPayload context, String bearer, IdentityContext identity) {
        if (!authorization.isAdministrator(identity))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dashboard evidence requires an authenticated administrator.");
        authorization.requireAudienceAccess(identity, context);
        var policy = policies.policyFor(identity.tenantId());
        if (!policy.enabled() || !policy.allowsTool("admin.dashboard.read"))
            return gap("Live dashboard evidence is not permitted by the tenant's admin.dashboard.read policy.");
        if (bearer == null || !bearer.regionMatches(true, 0, "Bearer ", 0, 7) || bearer.length() <= 7
                || bearer.indexOf('\r') >= 0 || bearer.indexOf('\n') >= 0)
            return gap("Live dashboard evidence requires the original authenticated bearer request.");

        final Filters filters;
        try { filters = Filters.parse(context); }
        catch (RuntimeException invalid) {
            return gap("Dashboard filters are not ready or invalid. Select a valid date range and organization scope before requesting live evidence.");
        }

        Instant collectedAt = Instant.now();
        List<String> facts = new ArrayList<>(), gaps = new ArrayList<>();
        Map<String, String> period = filters.query(true);
        Map<String, String> financial = new LinkedHashMap<>(period);
        if (filters.currency() != null) financial.put("currency", filters.currency());
        Map<String, String> current = filters.query(false);
        current.put("state", "ACTIVE"); current.put("page", "0"); current.put("size", "25");
        Map<String, CompletableFuture<HttpResponse<byte[]>>> reads = new LinkedHashMap<>();
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            reads.put("Period charging outcome", read("/session/api/v1/sessions/admin/charging-success-rate", period, bearer));
            reads.put("Period billing overview", read("/billing/api/v1/admin/analytics/overview", financial, bearer));
            reads.put("Current session snapshot", read("/session/api/v1/sessions/admin/search", current, bearer));
            try { CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new))
                    .get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception unavailable) { /* Retain completed reads; each unavailable section is explicit below. */ }

            for (var entry : reads.entrySet()) {
                try {
                    var pending = entry.getValue();
                    if (!pending.isDone() || pending.isCompletedExceptionally() || pending.isCancelled())
                        throw new IllegalStateException("Unavailable read");
                    var response = pending.join();
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        gaps.add(entry.getKey() + " is not permitted for your current account or scope.");
                        continue;
                    }
                    if (response.statusCode() != 200) throw new IllegalStateException("Unavailable read");
                    JsonNode body = mapper.readTree(response.body());
                    if (body == null || !body.isObject()) throw new IllegalStateException("Invalid response");
                    // Parse a whole section before publishing it, so malformed data cannot leave partial facts.
                    List<String> sectionFacts = new ArrayList<>(), sectionGaps = new ArrayList<>();
                    switch (entry.getKey()) {
                        case "Period charging outcome" -> outcome(body, filters, sectionFacts);
                        case "Period billing overview" -> billing(body, filters, sectionFacts, sectionGaps);
                        case "Current session snapshot" -> current(body, filters, collectedAt, sectionFacts, sectionGaps);
                        default -> throw new IllegalStateException("Unknown dashboard section");
                    }
                    facts.addAll(sectionFacts); gaps.addAll(sectionGaps);
                } catch (RuntimeException unavailable) {
                    gaps.add(entry.getKey() + " unavailable or incomplete; no zero count or healthy state is inferred.");
                }
            }
        } finally {
            reads.values().forEach(pending -> { if (!pending.isDone()) pending.cancel(true); });
        }
        if (!facts.isEmpty()) facts.add(0, "Dashboard evidence retrieved at " + collectedAt + "; selected period="
                + filters.from() + " to " + filters.to() + "; " + filters.scopeDescription()
                + ".");
        gaps.add("Charger health, payment settlement failures, and unread notifications could not be checked for these filters.");
        return new BackendDiagnosticsClient.DiagnosticsSnapshot(List.copyOf(facts), List.copyOf(gaps));
    }

    private CompletableFuture<HttpResponse<byte[]>> read(String path, Map<String, String> query, String bearer) {
        String encoded = query.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(gatewayUrl + path + "?" + encoded))
                .timeout(budget).header("Accept", "application/json").header("Authorization", bearer).GET().build();
        return http.sendAsync(request, ignored -> new SupportMcpClient.BoundedBody(MAX_BYTES));
    }

    private static void outcome(JsonNode body, Filters filters, List<String> facts) {
        if (!filters.from().equals(Instant.parse(requiredText(body, "from")))
                || !filters.to().equals(Instant.parse(requiredText(body, "to"))))
            throw new IllegalStateException("Date range mismatch");
        long successful = count(body, "successfulSessions"), failed = count(body, "failedSessions");
        long eligible = count(body, "eligibleSessions");
        long manual = count(body, "excludedManualCancellations"), payment = count(body, "excludedDriverPaymentFailures");
        BigDecimal rate = number(body, "chargingSuccessRate");
        if (successful > Long.MAX_VALUE - failed || eligible != successful + failed || rate.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalStateException("Inconsistent outcome counts");
        if (eligible == 0) {
            facts.add("No eligible completed/failed sessions in the selected period, so a charging success rate cannot be established. Excluded "
                    + manual + " manual cancellations and " + payment + " invalid credit-card sessions. Counts use session creation time and cover all currencies.");
            return;
        }
        facts.add("Selected period: " + failed + " of " + eligible + " eligible sessions failed (" + rate.toPlainString()
                + "% charging success); " + successful + " completed/billed. Excluded " + manual + " manual cancellations and " + payment
                + " invalid credit-card sessions. Counts use session creation time and cover all currencies. Completed/billed does not confirm payment capture.");
    }

    private static void billing(JsonNode body, Filters filters, List<String> facts, List<String> gaps) {
        long sessions = count(body, "totalSessions");
        BigDecimal energy = number(body, "totalEnergyKwh");
        String updated = body.path("lastUpdatedAt").isNull() || body.path("lastUpdatedAt").isMissingNode()
                ? "unavailable" : Instant.parse(requiredText(body, "lastUpdatedAt")).toString();
        String money = "";
        if (filters.currency() != null) {
            if (!filters.currency().equals(requiredText(body, "currency"))) throw new IllegalStateException("Currency mismatch");
            money = ", " + number(body, "totalRevenue").toPlainString() + " " + filters.currency() + " recorded revenue";
        } else gaps.add("Monetary totals omitted because no single reporting currency is selected; mixed currencies are not added together.");
        facts.add("Billing for the selected period (" + (filters.currency() == null ? "all currencies" : filters.currency()) + "): " + sessions
                + " sessions, " + energy.toPlainString() + " kWh" + money + ". Latest recorded event: " + updated
                + ". Billing uses receipt/event dates and can lag live sessions; billing data does not confirm payment settlement.");
    }

    private static void current(JsonNode body, Filters filters, Instant collectedAt, List<String> facts, List<String> gaps) {
        JsonNode content = body.path("content");
        if (!content.isArray() || content.size() > 25) throw new IllegalStateException("Invalid session page");
        List<CurrentSession> matching = new ArrayList<>();
        for (JsonNode row : content) {
            if (!row.isObject()) throw new IllegalStateException("Invalid session row");
            if (!filters.matches(row)) continue;
            String status = requiredText(row, "status");
            if (!ACTIVE_STATES.contains(status)) throw new IllegalStateException("Invalid active session state");
            UUID id = UUID.fromString(requiredText(row, "id"));
            matching.add(new CurrentSession(id, status));
        }
        List<String> states = new ArrayList<>();
        ACTIVE_STATES.forEach(state -> {
            long count = matching.stream().filter(row -> state.equals(row.status())).count();
            if (count > 0) states.add(count + " " + state.toLowerCase(Locale.ROOT));
        });
        facts.add("Current session snapshot at " + collectedAt + " (independent of selected date/currency): " + matching.size()
                + " matching sessions in the first " + content.size() + " returned records (up to 25): " + (states.isEmpty() ? "no matching sessions in this sample" : String.join(", ", states))
                + ". These are sample counts, not fleet totals. A session's stage alone does not prove it is stuck or has failed.");
        matching.stream().sorted(Comparator.comparingInt(row -> ACTIVE_STATES.indexOf(row.status()))).limit(5)
                .forEach(row -> facts.add("Current session to inspect: " + row.id() + "; status=" + row.status() + ". Select this session for evidence-based investigation."));
        gaps.add("Current-session coverage is limited to the first 25 search results. Only sessions matching the selected organization are shown; this sample cannot establish fleet totals or confirm that everything is healthy.");
    }

    private static long count(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalStateException("Invalid count");
        return value.longValue();
    }

    private static BigDecimal number(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isNumber()) throw new IllegalStateException("Invalid amount");
        BigDecimal result = value.decimalValue();
        if (result.signum() < 0 || result.precision() > 30 || Math.abs(result.scale()) > 12)
            throw new IllegalStateException("Invalid amount");
        return result;
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().length() > 160 || value.asText().isBlank())
            throw new IllegalStateException("Invalid text");
        return value.asText();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("*", "%2A"); }
    private static BackendDiagnosticsClient.DiagnosticsSnapshot gap(String message) {
        return new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of(message));
    }

    private record CurrentSession(UUID id, String status) { }

    private record Filters(Instant from, Instant to, String currency, String enterprise, String network, String location) {
        static Filters parse(ContextPayload context) {
            if (context == null || !"dashboard".equals(context.screen()) || context.attributes() == null
                    || !"true".equals(context.attributes().get("filtersReady"))) throw new IllegalArgumentException("Filters not ready");
            Map<String, String> attributes = context.attributes();
            Instant from = Instant.parse(attributes.get("from")), to = Instant.parse(attributes.get("to"));
            if (from.isAfter(to) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0)
                throw new IllegalArgumentException("Invalid range");
            String currency = selection(attributes.get("currency"));
            if (currency != null && !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
            String enterprise = id(attributes.get("filterEnterpriseId"));
            String network = id(attributes.get("filterNetworkId"));
            String location = id(attributes.get("filterLocationId"));
            if (context.locationId() != null && !context.locationId().isBlank() && !context.locationId().equalsIgnoreCase(location))
                throw new IllegalArgumentException("Conflicting location selection");
            return new Filters(from, to, currency, enterprise, network, location);
        }

        Map<String, String> query(boolean period) {
            Map<String, String> result = new LinkedHashMap<>();
            if (period) { result.put("from", from.toString()); result.put("to", to.toString()); }
            if (enterprise != null) result.put("enterpriseId", enterprise);
            if (network != null) result.put("networkId", network);
            if (location != null) result.put("locationId", location);
            return result;
        }

        boolean matches(JsonNode row) {
            return same(enterprise, row.path("enterpriseId")) && same(network, row.path("networkId")) && same(location, row.path("locationId"));
        }
        String scopeDescription() {
            return "enterprise=" + (enterprise == null ? "all authorized" : enterprise) + "; network=" + (network == null ? "all authorized" : network)
                    + "; location=" + (location == null ? "all authorized" : location);
        }
        private static boolean same(String expected, JsonNode actual) { return expected == null || (actual.isTextual() && expected.equalsIgnoreCase(actual.asText())); }
        private static String id(String value) {
            String normalized = selection(value);
            if (normalized != null && !normalized.matches("[A-Z0-9][A-Z0-9._*:-]{0,159}")) throw new IllegalArgumentException("Invalid scope ID");
            return normalized;
        }
        private static String selection(String value) {
            if (value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())) return null;
            return value.trim().toUpperCase(Locale.ROOT);
        }
    }
}
