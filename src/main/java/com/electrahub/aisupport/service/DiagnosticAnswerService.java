package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DiagnosticAnswerService {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticAnswerService.class);
    private static final Pattern CHARGER_PORTS_PATTERN = Pattern.compile(
            "charger\\s+(\\S+)\\s+status\\s+is\\s+(\\S+)\\s+with\\s+(\\d+)\\s+available\\s+port\\(s\\)\\s+and\\s+(\\d+)\\s+busy\\s+port\\(s\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTOR_PATTERN = Pattern.compile(
            "connector\\s+(\\S+)\\s+is\\s+(\\S+)\\s+available=(true|false)",
            Pattern.CASE_INSENSITIVE);
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
        if (isRevenueDashboardQuestion(message, safeContext)) {
            fallback = totalRevenueMetric(safeContext);
        } else if (isChargerAvailabilityQuestion(message)) {
            fallback = chargerAvailability(safeContext, diagnostics);
        } else if (isRemoteStopIdleQuestion(message)) {
            fallback = remoteStopIdleFee(safeContext, diagnostics);
        } else if (message.contains("simulator") && containsAny(message, "security code", "unplug", "mobile app", "link")) {
            fallback = simulatorSecureUnplug(safeContext, diagnostics);
        } else if (isCardPresentQuestion(message)) {
            fallback = cardPresentAdminView(safeContext, diagnostics);
        } else if (isStartFailureQuestion(message)) {
            fallback = chargingUnavailable(safeContext, diagnostics);
        } else if (isLastReceiptQuestion(message)) {
            fallback = receiptLookupNeedsSelection(safeContext, diagnostics);
        } else if (isSpendAnalyticsQuestion(message)) {
            fallback = spendAnalyticsNeedsReport(safeContext);
        } else if (isUsageAnalyticsQuestion(message)) {
            fallback = usageAnalyticsNeedsReport(safeContext);
        } else if (isTripDataQuestion(message)) {
            fallback = tripDataUnavailable(safeContext);
        } else if (isPricingComparisonQuestion(message)) {
            fallback = pricingComparisonNeedsContext(safeContext);
        } else if (isFindChargerQuestion(message)) {
            fallback = chargerAlternatives(userMessage, safeContext);
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
        if (requiresExactProjectAnswer(fallback.toolName())) {
            log.info("Sparky using deterministic fallback reason=exact_project_flow tool={}", fallback.toolName());
            return fallback;
        }
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
        if (looksLikePromptLeak(completion.answer())) {
            log.warn("Sparky using deterministic fallback reason=llm_prompt_leak provider={} model={} tool={}",
                    completion.provider(), completion.model(), fallback.toolName());
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
        String base = """
                I can help check that. A charging start failure usually means the charger command could not be completed at that moment.

                Most likely causes:
                - the charger was not connected to ElectraHub
                - the connector was not actually available
                - another session was still preparing or finishing
                - wallet/payment eligibility failed before the remote start could complete

                Try refreshing the charger screen. If the charger still shows unavailable, pick another connector or contact support at %s.
                """.formatted(properties.supportEmail()).trim();
        if (!hasLiveEntityContext(context)) {
            return new DiagnosticAnswer(
                    "diagnose_charging_start",
                    base + "\n\nI do not have a selected charger, connector, or session id in this chat context, so I cannot confirm the exact failed start from live backend data.",
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "diagnose_charging_start",
                enrich(base, diagnostics),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer receiptLookupNeedsSelection(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        String base = """
                I cannot open a receipt from chat unless a specific session is selected.

                Open the charging history entry or receipt screen for the session, then ask again. With a session selected, Sparky can use that session context instead of guessing.
                """.trim();
        if (context == null || isBlank(context.sessionId())) {
            return new DiagnosticAnswer(
                    "explain_receipt_lookup",
                    base,
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "explain_receipt_lookup",
                enrich(base, diagnostics),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer spendAnalyticsNeedsReport(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_spend_analytics_gap",
                """
                        I cannot calculate monthly spend from chat yet because this request needs a dated receipt/session aggregation API.

                        Use the history or payments report for the selected month. Sparky should only give a total after the backend provides the month window and completed receipt totals.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer usageAnalyticsNeedsReport(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_usage_analytics_gap",
                """
                        I cannot calculate usage analytics from chat yet because this request needs a completed-session aggregation.

                        For most-used station or yearly kWh, the backend should aggregate completed sessions by station and date window. Without that report, Sparky should not invent a station or kWh total.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer tripDataUnavailable(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_trip_data_unavailable",
                """
                        Sparky does not have trip distance data.

                        ElectraHub can answer charging session and receipt questions, but trips over a distance threshold require vehicle trip telemetry that is not part of the current Sparky diagnostics.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer pricingComparisonNeedsContext(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_pricing_context_needed",
                """
                        I cannot compare pricing plans precisely without a selected tariff, charger, location, or pricing-plan report.

                        Open the charger or pricing plan first, then ask about that specific plan. Sparky should use pricing-service tariff data and avoid estimating prices.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer chargerAlternatives(String userMessage, ContextPayload context) {
        BackendDiagnosticsClient.ChargerAlternatives alternatives =
                diagnosticsClient.findChargerAlternatives(context, userMessage, 3);
        if (!alternatives.alternatives().isEmpty()) {
            StringBuilder builder = new StringBuilder("I found these available ");
            builder.append(alternatives.connectorLabel()).append(" options");
            if (!isBlank(alternatives.referenceLocation())) {
                builder.append(" near ").append(alternatives.referenceLocation());
            }
            builder.append(":\n\n");
            for (BackendDiagnosticsClient.ChargerAlternative alternative : alternatives.alternatives()) {
                builder.append("- ")
                        .append(alternative.chargerName())
                        .append(" (")
                        .append(alternative.chargerId())
                        .append("), connector ")
                        .append(alternative.connectorId())
                        .append(" at ")
                        .append(alternative.locationName());
                if (!isBlank(alternative.distanceLabel())) {
                    builder.append(" - ").append(alternative.distanceLabel()).append(" away");
                }
                if (!isBlank(alternative.powerLabel())) {
                    builder.append(", ").append(alternative.powerLabel());
                }
                builder.append('\n');
            }
            builder.append("\nOpen one of these chargers from the map/list and confirm the connector still shows Available before starting.");
            return new DiagnosticAnswer(
                    "find_charger_alternatives",
                    builder.toString().trim(),
                    contextSummary(context)
            );
        }

        return new DiagnosticAnswer(
                "find_charger_alternatives",
                ("I checked live charger inventory, but I could not find another available %s connector%s right now.\n\n"
                        + "Try refreshing the map or widening the area. If the current charger is busy, choose a connector that shows Available before starting.")
                        .formatted(
                                alternatives.connectorLabel(),
                                isBlank(alternatives.referenceLocation()) ? "" : " near " + alternatives.referenceLocation()
                        ).trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer chargerAvailability(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        Optional<ChargerAvailability> availability = parseAvailability(diagnostics);
        if (availability.isEmpty()) {
            String missingContext = context == null || (isBlank(context.chargerId()) && isBlank(context.connectorId()))
                    ? " The app did not send the selected charger id, so I cannot verify this exact charger."
                    : "";
            return new DiagnosticAnswer(
                    "check_charger_availability",
                    ("I could not confirm live availability for this charger yet.%s Refresh the charger screen and try again, or pick another connector if the app shows it as busy.\n\n%s")
                            .formatted(missingContext, diagnostics.toAnswerText()).trim(),
                    contextSummary(context)
            );
        }

        ChargerAvailability state = availability.get();
        if (!state.available()) {
            return new DiagnosticAnswer(
                    "check_charger_availability",
                    """
                            No, this charger is not available right now.

                            The live status shows %s available port(s) and %s busy port(s)%s. If the connector is marked Charging, another driver is using it. Please choose another available connector or wait until this one becomes available.
                            """.formatted(
                                    state.availablePorts(),
                                    state.busyPorts(),
                                    state.connectorStatus().map(status -> ", and connector status is " + status).orElse("")
                            ).trim(),
                    contextSummary(context)
            );
        }

        return new DiagnosticAnswer(
                "check_charger_availability",
                """
                        Yes, this charger appears available right now.

                        The live status shows %s available port(s) and %s busy port(s)%s. You can start charging if the connector is physically ready.
                        """.formatted(
                                state.availablePorts(),
                                state.busyPorts(),
                                state.connectorStatus().map(status -> ", and connector status is " + status).orElse("")
                        ).trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer remoteStopIdleFee(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        if (isDriverAudience(context)) {
            return new DiagnosticAnswer(
                    "diagnose_idle_remote_stop",
                    """
                            Charging has stopped, but this charger has idle fees. Your session stays active until the vehicle is unplugged.

                            Open the simulator link from the charging screen and unplug the connector. Your receipt will be generated after unplug is completed.

                            If the screen still shows idle after unplugging, refresh the charging screen or contact support at %s.
                            """.formatted(properties.supportEmail()).trim(),
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "diagnose_idle_remote_stop",
                """
                        For an idle-fee charger, remote stop should pause charging and move the session to idle/SUSPENDED. The session should remain active and idle fee can continue until the vehicle is unplugged.

                        Support should check session-service state first: remoteStopRequestedAt, status, idleStartedAt, unplugRequiredToStop, and whether a terminal/receipt event was emitted too early.

                        Then check ocpp-service and simulator state: connector Redis state, StopTransaction/TransactionEvent, StatusNotification, and the unplug event. The receipt should be generated only after unplug or terminal settlement.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer simulatorSecureUnplug(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        if (isDriverAudience(context)) {
            return new DiagnosticAnswer(
                    "diagnose_simulator_secure_unplug",
                    """
                            When you open the simulator from the app, the access code should already be included. You should not need to type it manually.

                            Tap unplug to finish the session. If there is no active charging or idle session, unplug should not ask for a code.

                            If the unplug button is missing or the code is still requested, refresh the link from the charging screen.
                            """.trim(),
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "diagnose_simulator_secure_unplug",
                """
                        When the mobile app opens the simulator link for the active session, the security code should be passed in the URL and pre-filled/hidden. The driver should not have to type the code again.

                        If idle fee is enabled and the session is active/idle, the simulator should authorize unplug using that code and then emit the unplug/status events.

                        If idle fee is disabled or there is no active session, unplug should not require a security code.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer cardPresentAdminView(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        if (isDriverAudience(context)) {
            return new DiagnosticAnswer(
                    "explain_card_present_admin_payment",
                    """
                            If you started charging by tapping a credit card, the session may not be linked to your app account.

                            Your receipt should show Credit Card as the payment method, with masked card details when available.

                            ElectraHub should not show the full card number or raw processor transaction id.
                            """.trim(),
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "explain_card_present_admin_payment",
                """
                        For a tap-credit-card/card-present session, admin should see the payment method as Credit Card, with a masked card number when available.

                        If ElectraHub authorized the card-present payment, show the ElectraHub payment authorization id. Do not show raw processor transaction ids or full card details.

                        These sessions may be anonymous because the driver account may not be recognized from a physical card tap.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer totalRevenueMetric(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_admin_total_revenue",
                """
                        Total revenue on the admin dashboard should represent completed charging revenue for the selected dashboard date filter.

                        Admin should verify the same filter against completed charging sessions and receipts. The total should include billable charging amounts such as energy, idle fees, session fees, and taxes after applicable subscription discounts, and it should not count active or failed sessions.

                        If the percentage change looks wrong, compare the current filter window with the previous equivalent window and check whether the dashboard API is using the same completed-session timestamp and revenue fields as the receipts.
                        """.trim(),
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
        String base = """
                A session stuck in Preparing means the app received the start response, but ElectraHub is still waiting for the charger to confirm charging has begun.

                Keep the app open for a few seconds. If power and cost do not start moving, the charger may be offline, busy, or not sending meter updates.
                """.trim();
        if (!hasLiveEntityContext(context)) {
            return new DiagnosticAnswer(
                    "diagnose_session_state",
                    base + "\n\nI do not have the active session or charger id in this chat context, so I cannot inspect the exact session state yet.",
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "diagnose_session_state",
                enrich(base, diagnostics),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer heartbeat(ContextPayload context, BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        String base = """
                Charger availability depends on recent heartbeat messages from the charger.

                If the charger stops sending heartbeat events, ElectraHub marks it unavailable so drivers do not start sessions on a charger that cannot receive commands.
                """.trim();
        if (context == null || isBlank(context.chargerId())) {
            return new DiagnosticAnswer(
                    "check_charger_liveness",
                    base + "\n\nI do not have a selected charger id in this chat context, so I cannot check the exact OCPP heartbeat.",
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "check_charger_liveness",
                enrich(base, diagnostics),
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

    private Optional<ChargerAvailability> parseAvailability(BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        if (diagnostics == null) {
            return Optional.empty();
        }

        Integer availablePorts = null;
        Integer busyPorts = null;
        String chargerStatus = null;
        String connectorStatus = null;
        Boolean connectorAvailable = null;

        for (String fact : diagnostics.facts()) {
            Matcher chargerMatcher = CHARGER_PORTS_PATTERN.matcher(fact);
            if (chargerMatcher.find()) {
                chargerStatus = chargerMatcher.group(2);
                availablePorts = Integer.parseInt(chargerMatcher.group(3));
                busyPorts = Integer.parseInt(chargerMatcher.group(4));
                continue;
            }

            Matcher connectorMatcher = CONNECTOR_PATTERN.matcher(fact);
            if (connectorMatcher.find()) {
                connectorStatus = connectorMatcher.group(2);
                connectorAvailable = Boolean.parseBoolean(connectorMatcher.group(3));
            }
        }

        if (availablePorts == null && connectorAvailable == null && chargerStatus == null) {
            return Optional.empty();
        }

        boolean statusBlocksAvailability = containsAny(normalize(chargerStatus),
                "charging", "occupied", "busy", "inoperative", "unavailable", "faulted", "blocked", "reserved")
                || containsAny(normalize(connectorStatus),
                "charging", "occupied", "busy", "inoperative", "unavailable", "faulted", "blocked", "reserved");
        boolean hasAvailablePort = availablePorts == null || availablePorts > 0;
        boolean connectorAllowsUse = connectorAvailable == null || connectorAvailable;
        boolean available = hasAvailablePort && connectorAllowsUse && !statusBlocksAvailability;

        return Optional.of(new ChargerAvailability(
                available,
                availablePorts == null ? 0 : availablePorts,
                busyPorts == null ? 0 : busyPorts,
                Optional.ofNullable(connectorStatus)));
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

    private static boolean hasLiveEntityContext(ContextPayload context) {
        return context != null
                && (!isBlank(context.sessionId())
                || !isBlank(context.chargerId())
                || !isBlank(context.connectorId()));
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCardPresentQuestion(String message) {
        return containsAny(message, "tap credit", "tapped my credit", "card present", "credit card")
                && containsAny(message, "payment", "transaction id", "admin", "receipt", "charge", "session");
    }

    private static boolean isRemoteStopIdleQuestion(String message) {
        return containsAny(message, "remote stop", "stop charging", "stop request")
                && containsAny(message, "idle", "idle fee", "receipt", "unplug", "still active");
    }

    private static boolean isStartFailureQuestion(String message) {
        return containsAny(message, "start fail", "start failed", "failed to start", "why did start fail",
                "503", "unavailable", "temporarily unavailable", "remote start failed");
    }

    private static boolean isRevenueDashboardQuestion(String message, ContextPayload context) {
        String screen = context == null || context.screen() == null ? "" : context.screen().toLowerCase();
        String resourceType = context == null || context.resourceType() == null ? "" : context.resourceType().toLowerCase();
        return containsAny(message, "total revenue", "revenue", "sales", "income")
                && (message.length() <= 80 || screen.contains("dashboard") || resourceType.contains("dashboard"));
    }

    private static boolean isChargerAvailabilityQuestion(String message) {
        return containsAny(message, "is this charger available", "charger available", "connector available", "available to charge")
                || (containsAny(message, "available", "free", "busy", "occupied")
                && containsAny(message, "charger", "connector", "station"));
    }

    private static boolean isLastReceiptQuestion(String message) {
        return containsAny(message, "show last receipt", "last receipt", "latest receipt", "open receipt", "show receipt");
    }

    private static boolean isSpendAnalyticsQuestion(String message) {
        return containsAny(message, "how much did i spend", "spend last month", "spent last month", "monthly spend", "total spend");
    }

    private static boolean isUsageAnalyticsQuestion(String message) {
        return containsAny(message, "most used station", "total kwh", "kwh this year", "yearly kwh", "energy this year");
    }

    private static boolean isTripDataQuestion(String message) {
        return containsAny(message, "trips over", "trip over", "trip distance", "over 100 km", "over 100km");
    }

    private static boolean isPricingComparisonQuestion(String message) {
        return containsAny(message, "compare pricing", "compare price", "pricing plans", "price plans", "compare tariffs");
    }

    private static boolean isFindChargerQuestion(String message) {
        return containsAny(message, "find another", "find a charger", "another ccs", "nearby charger", "search charger");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean looksLikePromptLeak(String answer) {
        String normalized = answer == null ? "" : answer.toLowerCase();
        return normalized.contains("if the user asks")
                || normalized.contains("response rules")
                || normalized.contains("project knowledge:")
                || normalized.contains("backend facts:");
    }

    private static boolean isDriverAudience(ContextPayload context) {
        if (context == null) {
            return true;
        }
        String audience = context.audience() == null ? "" : context.audience().toLowerCase();
        String screen = context.screen() == null ? "" : context.screen().toLowerCase();
        if (audience.contains("admin") || audience.contains("support") || audience.contains("csr")) {
            return false;
        }
        return audience.isBlank()
                || audience.contains("driver")
                || audience.contains("owner")
                || screen.contains("livecharging")
                || screen.contains("activecharging")
                || screen.contains("mobile");
    }

    private static boolean requiresExactProjectAnswer(String toolName) {
        return "diagnose_idle_remote_stop".equals(toolName)
                || "diagnose_simulator_secure_unplug".equals(toolName)
                || "explain_card_present_admin_payment".equals(toolName)
                || "explain_admin_total_revenue".equals(toolName)
                || "check_charger_availability".equals(toolName)
                || "diagnose_charging_start".equals(toolName)
                || "diagnose_session_state".equals(toolName)
                || "check_charger_liveness".equals(toolName)
                || "explain_receipt_lookup".equals(toolName)
                || "explain_spend_analytics_gap".equals(toolName)
                || "explain_usage_analytics_gap".equals(toolName)
                || "explain_trip_data_unavailable".equals(toolName)
                || "explain_pricing_context_needed".equals(toolName)
                || "find_charger_alternatives".equals(toolName);
    }

    public record DiagnosticAnswer(String toolName, String text, String contextSummary) {
    }

    private record ChargerAvailability(boolean available,
                                       int availablePorts,
                                       int busyPorts,
                                       Optional<String> connectorStatus) {
    }
}
