package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded customer-support prose from typed, allowlisted parts of verified session facts. */
public final class SupportSessionAnswerPresenter {
    private static final String UUID_TEXT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern SESSION = Pattern.compile("^Session (" + UUID_TEXT + "): (.+)$");
    private static final Pattern EVENT = Pattern.compile("^Event " + UUID_TEXT + " at (\\S+): ([A-Z_]+)$");
    private static final Pattern COVERAGE = Pattern.compile("^([A-Z_]+) count=([0-9]+), first=([^,]+), last=([^,]+)$");
    private static final Pattern TIMELINE = Pattern.compile("^Session event timeline: ([0-9]+) persisted lifecycle events;.*$");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private SupportSessionAnswerPresenter() {}

    public static String present(BackendDiagnosticsClient.DiagnosticsSnapshot snapshot, ContextPayload context) {
        Evidence evidence = new Evidence(snapshot);
        String selectedId = context == null ? null : uuid(context.sessionId());
        if (selectedId == null && context != null && "session".equalsIgnoreCase(context.resourceType())) selectedId = uuid(context.resourceId());
        if (selectedId != null && evidence.id != null && !selectedId.equalsIgnoreCase(evidence.id)) {
            return "The returned evidence does not match the selected session. No diagnosis is confirmed. Reopen the session and retry the authorized analysis.";
        }
        String sessionId = selectedId == null ? evidence.id : selectedId;
        if (!evidence.hasRecognizedFacts()) {
            return "Session analysis is unavailable. No session failure cause is confirmed."
                    + (sessionId == null ? "" : "\n\nSelected session: " + sessionId + ".")
                    + "\n\nCheck your access to this session and retry. If the problem continues, review the session records with support; the available evidence is insufficient to explain what happened.";
        }

        String status = code(evidence.session.get("status"));
        String compliance = code(evidence.invoice.get("complianceStatus"));
        Settlement settlement = evidence.settlement();
        StringBuilder answer = new StringBuilder(assessment(status));
        if (settlement.failed) answer.append(" A recorded settlement failure also needs review.");
        if ("INCOMPLETE".equals(compliance)) answer.append(" The invoice is marked incomplete and needs review.");
        if (sessionId != null) answer.append("\n\nSession: ").append(sessionId).append('.');
        appendPeriod(answer, evidence);

        List<String> details = new ArrayList<>();
        details.add(start(evidence));
        details.add(payment(evidence, settlement));
        details.add(metering(evidence));
        details.add(subscription(evidence));
        details.add(billing(evidence));
        answer.append("\n\n");
        details.forEach(detail -> answer.append("- ").append(detail).append('\n'));

        LinkedHashSet<String> checks = checks(evidence, status, compliance, settlement);
        if (!checks.isEmpty()) {
            answer.append("\nNext checks:\n");
            checks.stream().limit(5).forEach(check -> answer.append("- ").append(check).append('\n'));
        }
        return answer.toString().trim();
    }

    private static String assessment(String status) {
        if (status == null) return "The session's current lifecycle state could not be verified; a stuck session or its cause is not confirmed.";
        return switch (status) {
            case "COMPLETED", "BILLED" -> "The session is recorded as " + status.toLowerCase(Locale.ROOT) + "; it is no longer in an active charging state. This alone does not prove payment capture.";
            case "PREPARING", "PENDING" -> "The start phase is incomplete. The recorded state alone does not establish that the session is stuck or identify a cause.";
            case "ACTIVE" -> "The session is recorded as active. Missing or stale telemetry alone does not prove it is stuck or that charging stopped.";
            case "SUSPENDED", "FINISHING" -> "The session is recorded as " + status.toLowerCase(Locale.ROOT) + " and has not reached terminal completion. Stop confirmation and payment settlement need checking.";
            case "INVALID" -> "The session is recorded as invalid. Review its start, stop and authorization-release evidence before advising a retry.";
            default -> "The recorded lifecycle state is not recognized by this summary; a stuck session or its cause is not confirmed.";
        };
    }

    private static void appendPeriod(StringBuilder answer, Evidence e) {
        Instant start = instant(e.session.get("startedAt")), stop = instant(e.session.get("stoppedAt"));
        if (start == null) {
            answer.append(" Recorded charging start time is unavailable.");
        } else if (stop == null) {
            answer.append(" Recorded charging start: ").append(UTC.format(start)).append("; end time unavailable.");
        } else if (stop.isBefore(start)) {
            answer.append(" Recorded start/end times are inconsistent; duration cannot be verified.");
        } else {
            BigDecimal seconds = BigDecimal.valueOf(Duration.between(start, stop).toMillis(), 3).setScale(1, RoundingMode.HALF_UP);
            answer.append(" Recorded charging period: ").append(UTC.format(start)).append(" to ")
                    .append(when(stop, start)).append(" (").append(number(seconds)).append(" seconds).");
        }
        String reason = code(e.session.get("stopReason"));
        if (reason != null) answer.append(" Stop reason: ").append(reason.toLowerCase(Locale.ROOT).replace('_', ' ')).append('.');
    }

    private static String start(Evidence e) {
        List<String> milestones = new ArrayList<>();
        milestone(milestones, e, "START_REQUESTED", "requested");
        milestone(milestones, e, "PAYMENT_AUTHORIZED", "payment authorized");
        milestone(milestones, e, "REMOTE_START_ACCEPTED", "remote start accepted");
        milestone(milestones, e, "OCPP_START_TRANSACTION", "charger start event");
        String text = "Start: " + (milestones.isEmpty() ? "milestone timestamps unavailable." : String.join("; ", milestones) + ".");
        String confirmation = code(e.confirmation.get("status"));
        if (confirmation != null) text += " Start/payment confirmation is recorded as " + confirmation.toLowerCase(Locale.ROOT).replace('_', ' ') + ".";
        if (milestones.size() < 4) text += " The complete start sequence is not verified.";
        return text;
    }

    private static void milestone(List<String> target, Evidence e, String type, String label) {
        EventTimes times = e.events.get(type);
        if (times != null && times.first != null) target.add(label + " " + when(times.first, instant(e.session.get("startedAt"))));
    }

    private static String payment(Evidence e, Settlement settlement) {
        String amount = quantity(e.authorization.get("amount"), "[A-Z]{3}");
        String status = code(e.authorization.get("status"));
        String text = "Payment: " + (amount == null ? "authorization amount unavailable" : "recorded authorization " + amount)
                + (status == null ? "; authorization status unavailable." : ", status " + status + ".")
                + " This is not proof of the current bank hold or capture.";
        if (settlement.at != null) {
            text += " " + (settlement.complete ? "Latest recorded settlement " : "A recorded settlement event ")
                    + (settlement.failed ? "failed" : "succeeded") + " at " + when(settlement.at, instant(e.session.get("startedAt"))) + ".";
            if (!settlement.complete) text += " The complete settlement history is unavailable.";
        } else text += " Payment settlement/capture is not verified.";
        return text;
    }

    private static String metering(Evidence e) {
        String energy = quantity(e.metering.get("energy"), "kWh");
        String sampleRows = e.metering.get("stored sample rows");
        Matcher count = Pattern.compile("^([0-9]+) \\(not the number of OCPP messages\\)$").matcher(Objects.toString(sampleRows, ""));
        String text = "Metering: " + (energy == null ? "session energy unavailable" : energy + " recorded")
                + (count.matches() ? "; " + count.group(1) + " stored meter sample rows." : "; stored sample count unavailable.");
        text += " Sample rows are not an OCPP message count.";
        if (e.gapContains("archived ocpp rows", "ocpp archive", "connector_window_only"))
            text += " The historical message exchange is not fully verified.";
        return text;
    }

    private static String subscription(Evidence e) {
        String plan = token(e.subscription.get("plan"));
        boolean allocation = uuid(e.subscription.get("allocation")) != null;
        String discount = quantity(e.subscription.get("discount"), "[A-Z]{3}");
        String covered = decimal(e.subscription.get("coveredEnergyKwh"));
        String text;
        if (plan != null) text = "Subscription: recorded plan " + plan + ".";
        else if (allocation) text = "Subscription: an allocation is recorded, but the plan is unavailable.";
        else text = "Subscription: no plan or allocation is recorded; subscription use cannot be confirmed.";
        if (discount != null) text += " Recorded discount: " + discount + ".";
        if ((plan != null || allocation) && covered != null) text += " Recorded covered energy: " + covered + " kWh.";
        return text;
    }

    private static String billing(Evidence e) {
        String currency = currency(e.bill.get("currency"));
        String total = decimal(e.bill.get("total"));
        String text = "Bill: " + (total == null ? "stored total unavailable." : "stored " + (e.provisionalBill ? "provisional " : "") + "total " + total + " " + Objects.toString(currency, "(currency unavailable)") + ".");
        List<String> parts = new ArrayList<>();
        for (String[] item : List.of(new String[]{"energy", "energy"}, new String[]{"time", "time"},
                new String[]{"sessionFee", "session fee"}, new String[]{"idle", "idle"})) {
            String amount = decimal(e.bill.get(item[0]));
            if (amount != null) parts.add(item[1] + " " + amount);
        }
        if (!parts.isEmpty()) text += " Recorded fees: " + String.join(", ", parts) + (currency == null ? "." : " " + currency + ".");
        String gross = decimal(e.bill.get("grossBeforeDiscountAndTax")), tax = decimal(e.bill.get("tax"));
        String benefit = decimal(e.arithmetic.get("recorded benefit"));
        if (gross != null && benefit != null && tax != null && total != null && e.arithmeticMatched()) {
            text += " Stored arithmetic " + gross + " − " + benefit + " + " + tax + " = " + total
                    + " matches at currency precision; tariff, cap and tax-policy correctness are not verified by this check.";
        } else if (e.arithmeticMismatch()) text += " Stored amounts require reconciliation; a billing error is not yet established.";
        else text += " Full bill arithmetic could not be verified.";
        String invoice = code(e.invoice.get("complianceStatus"));
        if (invoice != null) text += " Invoice compliance: " + invoice.toLowerCase(Locale.ROOT).replace('_', ' ') + ".";
        else text += " Invoice compliance is unavailable.";
        return text;
    }

    private static LinkedHashSet<String> checks(Evidence e, String status, String compliance, Settlement settlement) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if ("INCOMPLETE".equals(compliance)) result.add("Review the incomplete invoice's missing compliance details before treating it as a complete receipt.");
        if (settlement.failed) result.add("Review the latest payment attempt and processor evidence before any retry or refund.");
        if (e.arithmeticMismatch() || e.gapContains("billing", "tax", "invoice")) result.add("Reconcile stored fees, subscription benefit and tax against the effective pricing policy; do not infer missing amounts as zero.");
        if (Set.of("PREPARING", "PENDING", "SUSPENDED", "FINISHING", "INVALID").contains(Objects.toString(status, "")))
            result.add("Check the missing start/stop milestone and elapsed time against the configured threshold before calling the session stuck.");
        if (e.gapContains("ocpp", "archive", "timeline abbreviated")) result.add("Use the persisted session events for this investigation; recover transaction-correlated charger records if needed. Missing archive rows do not prove no messages were exchanged.");
        if (e.gapContains("ownership", "organization", "get_org_context", "tenant context")) result.add("Confirm the session's organization in your authorized support view; its ownership could not be fully verified here.");
        if (e.gapContains("authorization amount", "payment hold", "capture", "settlement") && !settlement.failed)
            result.add("Check the persisted authorization and settlement records before advising on a bank hold, capture or release.");
        if (result.isEmpty() && (!e.gaps.isEmpty() || e.session.isEmpty())) result.add("Some evidence checks remain incomplete. Review the selected session's detailed records before confirming a cause.");
        return result;
    }

    private static String when(Instant value, Instant anchor) {
        return anchor != null && value.atOffset(ZoneOffset.UTC).toLocalDate().equals(anchor.atOffset(ZoneOffset.UTC).toLocalDate())
                ? CLOCK.format(value) : UTC.format(value);
    }

    private static Instant instant(String value) {
        try { return value == null || value.length() > 40 ? null : Instant.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String uuid(String value) { return value != null && value.matches(UUID_TEXT) ? value : null; }
    private static String code(String value) { return value != null && value.matches("[A-Z][A-Z_]{0,47}") ? value : null; }
    private static String currency(String value) { return value != null && value.matches("[A-Z]{3}") ? value : null; }
    private static String token(String value) { return value != null && !"unavailable".equals(value) && uuid(value) == null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") ? value : null; }
    private static String decimal(String value) {
        if (value == null || !value.matches("-?[0-9]{1,18}(?:\\.[0-9]{1,12})?")) return null;
        return number(new BigDecimal(value));
    }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String quantity(String value, String unit) {
        if (value == null) return null;
        Matcher match = Pattern.compile("^(-?[0-9]{1,18}(?:\\.[0-9]{1,12})?) (" + unit + ")$").matcher(value);
        return match.matches() ? decimal(match.group(1)) + " " + match.group(2) : null;
    }

    private static Map<String, String> fields(String text) {
        Map<String, String> values = new HashMap<>();
        for (String field : text.split("; ")) {
            int equals = field.indexOf('=');
            if (equals > 0) values.put(field.substring(0, equals), field.substring(equals + 1));
        }
        return values;
    }

    private record Settlement(Instant at, boolean failed, boolean complete) {}
    private record EventTimes(Instant first, Instant last) {}

    private static final class Evidence {
        final List<String> facts, gaps;
        final Map<String, String> session = new HashMap<>(), confirmation, authorization, metering, subscription, bill, invoice, arithmetic;
        final Map<String, EventTimes> events = new HashMap<>();
        String id;
        boolean completeCoverage, provisionalBill;
        int visibleEvents;
        Integer totalEvents;
        Evidence(BackendDiagnosticsClient.DiagnosticsSnapshot snapshot) {
            facts = snapshot == null || snapshot.facts() == null ? List.of() : snapshot.facts().stream().filter(Objects::nonNull).limit(350).toList();
            gaps = snapshot == null || snapshot.gaps() == null ? List.of() : snapshot.gaps().stream().filter(Objects::nonNull).limit(350).toList();
            confirmation = section("Start/payment confirmation: ");
            authorization = section("Payment authorization snapshot: ");
            metering = section("Metering: ");
            subscription = section("Subscription snapshot: ");
            Map<String, String> terminal = section("Persisted billing snapshot (terminal session): ");
            provisionalBill = terminal.isEmpty();
            bill = provisionalBill ? section("Persisted billing snapshot (provisional active session): ") : terminal;
            invoice = section("Invoice snapshot: ");
            arithmetic = section("Persisted amount arithmetic check: ");
            for (String fact : facts) {
                if (fact.length() > 16_384) continue;
                Matcher sessionMatch = SESSION.matcher(fact), event = EVENT.matcher(fact), timeline = TIMELINE.matcher(fact);
                if (sessionMatch.matches() && id == null) { id = sessionMatch.group(1); session.putAll(fields(sessionMatch.group(2))); }
                if (event.matches()) {
                    Instant at = instant(event.group(1));
                    if (at != null) { visibleEvents++; mergeEvent(event.group(2), at, at); }
                }
                if (timeline.matches()) try { totalEvents = Integer.parseInt(timeline.group(1)); } catch (NumberFormatException ignored) { }
            }
            String coverage = first("Event coverage summary (all persisted session events, including omitted timeline rows): ");
            if (coverage != null && !coverage.isBlank()) {
                Map<String, EventTimes> summarized = new HashMap<>();
                boolean valid = true;
                for (String part : coverage.split("; ")) {
                    Matcher match = COVERAGE.matcher(part);
                    if (!match.matches() || !match.group(2).matches("[1-9][0-9]{0,8}")) { valid = false; break; }
                    Instant first = instant(match.group(3)), last = instant(match.group(4));
                    if (first == null || last == null || last.isBefore(first)) { valid = false; break; }
                    summarized.put(match.group(1), new EventTimes(first, last));
                }
                if (valid) { events.clear(); events.putAll(summarized); completeCoverage = true; }
            }
            if (totalEvents != null && totalEvents == visibleEvents) completeCoverage = true;
        }
        void mergeEvent(String type, Instant first, Instant last) {
            events.merge(type, new EventTimes(first, last), (a, b) -> new EventTimes(a.first.isBefore(b.first) ? a.first : b.first, a.last.isAfter(b.last) ? a.last : b.last));
        }
        Settlement settlement() {
            EventTimes success = events.get("PAYMENT_SETTLED"), failure = events.get("PAYMENT_SETTLEMENT_FAILED");
            if (success == null && failure == null) return new Settlement(null, false, completeCoverage);
            if (failure != null && (success == null || !failure.last.isBefore(success.last))) return new Settlement(failure.last, true, completeCoverage);
            return new Settlement(success.last, false, completeCoverage);
        }
        String first(String prefix) { return facts.stream().filter(value -> value.length() <= 16_384 && value.startsWith(prefix)).map(value -> value.substring(prefix.length())).findFirst().orElse(null); }
        Map<String, String> section(String prefix) { String value = first(prefix); return value == null ? Map.of() : fields(value); }
        boolean hasRecognizedFacts() { return !session.isEmpty() || !authorization.isEmpty() || !metering.isEmpty() || !bill.isEmpty() || !events.isEmpty(); }
        boolean arithmeticMatched() { return facts.stream().anyMatch(value -> value.startsWith("Persisted amount arithmetic check: ") && value.contains("; matches recorded expression at currency precision.")); }
        boolean arithmeticMismatch() { return facts.stream().anyMatch(value -> value.startsWith("Persisted amount arithmetic check: ") && value.contains("RECONCILIATION_REQUIRED")) || gapContains("arithmetic differs", "tax line", "discrepancy"); }
        boolean gapContains(String... needles) { return gaps.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> Arrays.stream(needles).anyMatch(value::contains)); }
    }
}
