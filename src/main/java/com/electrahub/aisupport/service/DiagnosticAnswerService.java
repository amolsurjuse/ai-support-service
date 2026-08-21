package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.text.NumberFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class DiagnosticAnswerService {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticAnswerService.class);
    private static final Pattern CHARGER_PORTS_PATTERN = Pattern.compile(
            "charger\\s+(\\S+)\\s+status\\s+is\\s+(\\S+)\\s+with\\s+(\\d+)\\s+available\\s+port\\(s\\)\\s+and\\s+(\\d+)\\s+busy\\s+port\\(s\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTOR_PATTERN = Pattern.compile(
            "connector\\s+(\\S+)\\s+is\\s+(\\S+)\\s+available=(true|false)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> LIVE_STREAM_TOOLS = Set.of(
            "driver_support_context", "check_charger_availability", "diagnose_charging_start",
            "diagnose_session_state", "check_charger_liveness", "find_charger_alternatives",
            "diagnose_idle_remote_stop", "prepare_remote_stop", "diagnose_simulator_secure_unplug");
    private final AiSupportProperties properties;
    private final PiiRedactor redactor;
    private final BackendDiagnosticsClient diagnosticsClient;
    private final LlmClient llmClient;
    private final SparkyAnswerQualityGuard qualityGuard;
    private final AdminCommandService adminCommandService;
    private final TenantAiPolicyService tenantPolicyService;
    private final BookStackKnowledgeClient bookStackKnowledgeClient;

    @Autowired
    DiagnosticAnswerService(AiSupportProperties properties,
                            PiiRedactor redactor,
                            BackendDiagnosticsClient diagnosticsClient,
                            LlmClient llmClient,
                            AdminCommandService adminCommandService,
                            TenantAiPolicyService tenantPolicyService,
                            BookStackKnowledgeClient bookStackKnowledgeClient) {
        this(properties, redactor, diagnosticsClient, llmClient, new SparkyAnswerQualityGuard(), adminCommandService,
                tenantPolicyService, bookStackKnowledgeClient);
    }

    DiagnosticAnswerService(AiSupportProperties properties,
                            PiiRedactor redactor,
                            BackendDiagnosticsClient diagnosticsClient,
                            LlmClient llmClient) {
        this(properties, redactor, diagnosticsClient, llmClient, new SparkyAnswerQualityGuard(), null, null, null);
    }

    DiagnosticAnswerService(AiSupportProperties properties,
                            PiiRedactor redactor,
                            BackendDiagnosticsClient diagnosticsClient,
                            LlmClient llmClient,
                            SparkyAnswerQualityGuard qualityGuard) {
        this(properties, redactor, diagnosticsClient, llmClient, qualityGuard, null, null, null);
    }

    DiagnosticAnswerService(AiSupportProperties properties,
                            PiiRedactor redactor,
                            BackendDiagnosticsClient diagnosticsClient,
                            LlmClient llmClient,
                            SparkyAnswerQualityGuard qualityGuard,
                            AdminCommandService adminCommandService,
                            TenantAiPolicyService tenantPolicyService,
                            BookStackKnowledgeClient bookStackKnowledgeClient) {
        this.properties = properties;
        this.redactor = redactor;
        this.diagnosticsClient = diagnosticsClient;
        this.llmClient = llmClient;
        this.qualityGuard = qualityGuard;
        this.adminCommandService = adminCommandService;
        this.tenantPolicyService = tenantPolicyService;
        this.bookStackKnowledgeClient = bookStackKnowledgeClient;
    }

    public DiagnosticAnswer answer(String userMessage, ContextPayload context, String authorization) {
        return answer(userMessage, context, authorization, legacyIdentity(authorization));
    }

    public DiagnosticAnswer answer(String userMessage, ContextPayload context, String authorization,
                                   IdentityContext identity) {
        String message = redactor.redact(userMessage).toLowerCase();
        ContextPayload safeContext = context == null ? new ContextPayload(null, null, null, null, null, null, null, "driver") : context;
        if (requiresSelectedRecord(safeContext)) {
            return selectedRecordRequired(safeContext);
        }
        Optional<DiagnosticAnswer> adminAnswer = answerAdminCommand(userMessage, safeContext, authorization, identity);
        if (adminAnswer.isPresent()) {
            return adminAnswer.get();
        }
        Optional<DiagnosticAnswer> conversational = conversationalAnswer(message);
        if (conversational.isPresent()) {
            return conversational.get();
        }
        BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics = collectDiagnostics(
                message, safeContext, authorization, identity);

        DiagnosticAnswer fallback = deterministicAnswer(message, userMessage, safeContext, diagnostics, identity);
        return llmAnswerOrFallback(userMessage, safeContext, diagnostics, fallback, identity);
    }

    public DiagnosticAnswer answerStreaming(String userMessage,
                                            ContextPayload context,
                                            String authorization,
                                            Consumer<String> onDelta) {
        return answerStreaming(userMessage, context, authorization, legacyIdentity(authorization), onDelta);
    }

    public DiagnosticAnswer answerStreaming(String userMessage,
                                            ContextPayload context,
                                            String authorization,
                                            IdentityContext identity,
                                            Consumer<String> onDelta) {
        String message = redactor.redact(userMessage).toLowerCase();
        ContextPayload safeContext = context == null ? new ContextPayload(null, null, null, null, null, null, null, "driver") : context;
        if (requiresSelectedRecord(safeContext)) {
            DiagnosticAnswer answer = selectedRecordRequired(safeContext);
            onDelta.accept(answer.text());
            return answer;
        }
        Optional<DiagnosticAnswer> adminAnswer = answerAdminCommand(userMessage, safeContext, authorization, identity);
        if (adminAnswer.isPresent()) {
            onDelta.accept(adminAnswer.get().text());
            return adminAnswer.get();
        }
        Optional<DiagnosticAnswer> conversational = conversationalAnswer(message);
        if (conversational.isPresent()) {
            onDelta.accept(conversational.get().text());
            return conversational.get();
        }
        BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics = collectDiagnostics(
                message, safeContext, authorization, identity);
        DiagnosticAnswer fallback = deterministicAnswer(message, userMessage, safeContext, diagnostics, identity);
        if (!llmClient.available()) {
            onDelta.accept(fallback.text());
            return fallback;
        }

        boolean live = LIVE_STREAM_TOOLS.contains(fallback.toolName());
        StringBuilder emitted = new StringBuilder();
        Consumer<String> streamConsumer = delta -> {
            emitted.append(delta);
            onDelta.accept(delta);
        };
        LlmClient.LlmPrompt prompt = new LlmClient.LlmPrompt(
                redactor.redact(userMessage), safeContext, fallback, diagnostics, tenantKnowledge(identity, userMessage));
        LlmClient.LlmCompletion completion = live
                ? llmClient.completeStreaming(prompt, streamConsumer)
                : llmClient.complete(prompt);
        if (!completion.ok() || completion.answer().isBlank()) {
            if (emitted.isEmpty()) {
                onDelta.accept(fallback.text());
            }
            return fallback;
        }
        SparkyAnswerQualityGuard.Evaluation evaluation = qualityGuard.evaluate(
                completion.answer(), fallback, userMessage, safeContext);
        if (!evaluation.accepted()) {
            log.warn("Sparky streaming quality guard rejected provider={} model={} tool={} rejection={} alreadyEmitted={}",
                    completion.provider(), completion.model(), fallback.toolName(), evaluation.reason(), !emitted.isEmpty());
            if (emitted.isEmpty()) {
                onDelta.accept(fallback.text());
                return fallback;
            }
            return new DiagnosticAnswer(fallback.toolName(), completion.answer(), fallback.contextSummary());
        }
        if (!live) {
            onDelta.accept(evaluation.answer());
        }
        log.info("Sparky streamed LLM answer provider={} model={} tool={} answerChars={}",
                completion.provider(), completion.model(), fallback.toolName(), evaluation.answer().length());
        return new DiagnosticAnswer(fallback.toolName(), evaluation.answer(), fallback.contextSummary());
    }

    private DiagnosticAnswer deterministicAnswer(String message,
                                                  String userMessage,
                                                  ContextPayload safeContext,
                                                  BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics,
                                                  IdentityContext identity) {

        DiagnosticAnswer fallback;
        if (isRevenueDashboardQuestion(message, safeContext)) {
            fallback = totalRevenueMetric(safeContext);
        } else if (isDashboardAttentionQuestion(message, safeContext)) {
            fallback = dashboardAttention(safeContext);
        } else if (isChargingSuccessQuestion(message, safeContext)) {
            fallback = chargingSuccessMonitoring(safeContext);
        } else if (isChargerAvailabilityQuestion(message)) {
            fallback = chargerAvailability(safeContext, diagnostics);
        } else if (isRemoteStopIdleQuestion(message)) {
            fallback = remoteStopIdleFee(safeContext, diagnostics);
        } else if (isRemoteStopPreparationQuestion(message)) {
            fallback = remoteStopPreparation(safeContext);
        } else if (message.contains("simulator") && containsAny(message, "security code", "unplug", "mobile app", "link")) {
            fallback = simulatorSecureUnplug(safeContext, diagnostics);
        } else if (isCardPresentQuestion(message)) {
            fallback = cardPresentAdminView(safeContext, diagnostics);
        } else if (isPastSessionDiagnosisQuestion(message)) {
            fallback = pastSessionDiagnosis(safeContext);
        } else if (isRfidAuthorizationQuestion(message)) {
            fallback = rfidAuthorization(safeContext);
        } else if (isPlugAndChargeQuestion(message)) {
            fallback = plugAndChargeAuthorization(safeContext);
        } else if (isPaymentAuthorizationQuestion(message)) {
            fallback = paymentAuthorization(safeContext);
        } else if (isExplicitAvailableQuestion(message)) {
            fallback = explicitAvailableStatus(safeContext);
        } else if (isChargerStatusManagementQuestion(message)) {
            fallback = chargerStatusManagement(safeContext);
        } else if (isFeeCapQuestion(message)) {
            fallback = pricingCaps(safeContext);
        } else if (isReceiptCostConsistencyQuestion(message)) {
            fallback = receiptCostConsistency(safeContext);
        } else if (isChargeExplanationQuestion(message)) {
            fallback = receiptLookupNeedsSelection(safeContext, diagnostics);
        } else if (isIdleFeeExplanationQuestion(message)) {
            fallback = pricingCaps(safeContext);
        } else if (isRealTimeCostQuestion(message)) {
            fallback = realTimeCost(safeContext);
        } else if (isSubscriptionQuestion(message)) {
            fallback = subscriptionLifecycle(message, safeContext);
        } else if (isChargingNotificationQuestion(message)) {
            fallback = chargingNotifications(safeContext);
        } else if (isNotificationLifecycleQuestion(message)) {
            fallback = notificationLifecycle(message, safeContext);
        } else if (isRbacQuestion(message)) {
            fallback = rbacScope(safeContext);
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
            fallback = chargerAlternatives(userMessage, safeContext, identity);
        } else if (isConnectorStatusMeaningQuestion(message)) {
            fallback = chargerAvailability(safeContext, diagnostics);
        } else if (message.contains("already_active") || message.contains("already active") || message.contains("in progress")) {
            fallback = alreadyActive(safeContext, diagnostics);
        } else if (message.contains("stuck") || message.contains("preparing")) {
            fallback = stuckPreparing(safeContext, diagnostics);
        } else if (message.contains("online") || message.contains("offline") || message.contains("heartbeat")) {
            fallback = heartbeat(safeContext, diagnostics);
        } else if (isAdministrativeAudience(safeContext)) {
            fallback = adminScreenGuidance(message, safeContext);
        } else {
            fallback = generalChargingHelp(safeContext, diagnostics);
        }

        return fallback;
    }

    private Optional<DiagnosticAnswer> conversationalAnswer(String message) {
        String normalized = message.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (normalized.matches("(?:what is|whats|what s) your name")
                || normalized.matches("who are you")
                || normalized.matches("tell me your name")) {
            return Optional.of(new DiagnosticAnswer(
                    "assistant_identity",
                    "I'm Sparky, ElectraHub's EV charging assistant.",
                    ""));
        }
        if (normalized.length() <= 40 && normalized.matches("(?:hi|hello|hey|good morning|good afternoon|good evening)(?: sparky)?")) {
            return Optional.of(new DiagnosticAnswer(
                    "assistant_greeting",
                    "Hi! I'm Sparky. How can I help with your ElectraHub charging experience?",
                    ""));
        }
        if (normalized.contains("what can you do") || normalized.contains("how can you help")) {
            return Optional.of(new DiagnosticAnswer(
                    "assistant_capabilities",
                    "I can help with charger availability, charging sessions, payments, receipts, and troubleshooting.",
                    ""));
        }
        if (normalized.length() <= 40 && normalized.matches("(?:thanks|thank you|thank you sparky|thanks sparky)")) {
            return Optional.of(new DiagnosticAnswer(
                    "assistant_courtesy",
                    "You're welcome! Ask me anytime you need help with ElectraHub charging.",
                    ""));
        }
        return Optional.empty();
    }

    private BackendDiagnosticsClient.DiagnosticsSnapshot collectDiagnostics(String message,
                                                                             ContextPayload context,
                                                                             String authorization,
                                                                             IdentityContext identity) {
        BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics =
                diagnosticsClient.collect(message, context, authorization, identity);
        if (diagnostics == null) {
            diagnostics = diagnosticsClient.collect(context, authorization);
        }
        return diagnostics == null
                ? new BackendDiagnosticsClient.DiagnosticsSnapshot(java.util.List.of(), java.util.List.of())
                : diagnostics;
    }

    private DiagnosticAnswer llmAnswerOrFallback(String userMessage,
                                                 ContextPayload context,
                                                 BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics,
                                                 DiagnosticAnswer fallback,
                                                 IdentityContext identity) {
        if (!llmClient.available()) {
            log.info("Sparky using deterministic fallback reason=llm_unavailable tool={} contextSummaryPresent={}",
                    fallback.toolName(), fallback.contextSummary() != null && !fallback.contextSummary().isBlank());
            return fallback;
        }
        LlmClient.LlmCompletion completion = llmClient.complete(new LlmClient.LlmPrompt(
                redactor.redact(userMessage),
                context,
                fallback,
                diagnostics,
                tenantKnowledge(identity, userMessage)
        ));
        if (!completion.ok() || completion.answer().isBlank()) {
            log.warn("Sparky using deterministic fallback reason=llm_completion_failed provider={} model={} tool={} error={}",
                    completion.provider(), completion.model(), fallback.toolName(), completion.error());
            return fallback;
        }
        SparkyAnswerQualityGuard.Evaluation evaluation = qualityGuard.evaluate(
                completion.answer(), fallback, userMessage, context);
        if (!evaluation.accepted()) {
            log.warn("Sparky using deterministic fallback reason=llm_quality_guard provider={} model={} tool={} rejection={}",
                    completion.provider(), completion.model(), fallback.toolName(), evaluation.reason());
            return fallback;
        }
        log.info("Sparky using LLM answer provider={} model={} tool={} answerChars={}",
                completion.provider(), completion.model(), fallback.toolName(), evaluation.answer().length());
        return new DiagnosticAnswer(fallback.toolName(), evaluation.answer(), fallback.contextSummary());
    }

    private String tenantKnowledge(IdentityContext identity, String userMessage) {
        if (tenantPolicyService == null || identity == null) {
            return "";
        }
        String knowledge = tenantPolicyService.policyFor(identity.tenantId()).knowledgeText();
        if (bookStackKnowledgeClient != null) {
            knowledge += "\n\nRole-scoped BookStack references:\n" + bookStackKnowledgeClient.search(userMessage, identity);
        }
        return knowledge;
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

    private DiagnosticAnswer pastSessionDiagnosis(ContextPayload context) {
        if (context != null && !isBlank(context.sessionId())) {
            return new DiagnosticAnswer(
                    "diagnose_past_session",
                    """
                            I can investigate the selected charging session %s. The diagnosis should use its session state, stop reason, charger and connector events, meter progression, and completed receipt.

                            If those session details are not yet available, refresh the selected history entry and try again. Do not infer a failure cause from missing diagnostics alone.
                            """.formatted(context.sessionId()).trim(),
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "diagnose_past_session",
                """
                        I cannot diagnose why a past charging session failed without the selected session. Missing session context is not itself the reason for the failure.

                        Open the charging history entry, then Sparky can check the charger, connector, session state, stop reason, and receipt for that specific session.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer dashboardAttention(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_dashboard_attention",
                """
                        Review the scoped dashboard for active sessions that are idle or stuck, failed starts, offline or faulted chargers, payment/settlement failures, and unread operational notifications.

                        Do not claim that a specific issue needs attention without the current dashboard facts. Open the affected session or charger to investigate it using the same date and access scope as the dashboard.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer chargingSuccessMonitoring(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_charging_success_monitoring",
                """
                        Monitor charging success rate from eligible charging attempts through terminal settlement, alongside failed-start reasons, charger availability, command latency, and sessions that remain idle or incomplete.

                        Manual driver cancellations and failures caused by a driver's own card must be classified separately rather than counted as charger-platform failures. Results must use the current admin access scope and date filter.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer remoteStopPreparation(ContextPayload context) {
        return new DiagnosticAnswer(
                "prepare_remote_stop",
                """
                        Before remotely stopping an active session, confirm the selected session and connector, its current charging state, and whether idle fees are enabled.

                        Remote stop pauses charging. If idle fees are enabled, the session remains idle until unplug and the receipt waits for terminal settlement. If idle fees are not enabled, receipt generation can proceed after the charger confirms the terminal event.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer chargerStatusManagement(ContextPayload context) {
        return new DiagnosticAnswer(
                "manage_charger_status",
                """
                        Before changing charger status, confirm the selected charger and connector, any active or reserved session, the last OCPP heartbeat, and the expected driver impact.

                        Marking a charger Inoperative must prevent new sessions and should be used for maintenance. Return it to service only after the charger reports a healthy status notification and recent heartbeat; never use the admin action to mask an unresolved charger fault.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer explicitAvailableStatus(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_explicit_available_status",
                """
                        After an unplug completes a session, the connector may emit an explicit Available status notification. That Available event identifies the connector and status; it must not include a transaction id.

                        The session must already have reached its terminal state before the connector is treated as available for a new driver.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer pricingCaps(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_pricing_caps",
                """
                        Session and idle-fee caps are enforced by the backend pricing flow, not by the mobile or admin UI. The session cap limits billable charging cost, and the idle-fee cap limits accumulated idle charges after charging pauses.

                        The active-session estimate and final receipt must use the configured tariff, taxes, discounts, and the same caps. The receipt should list charging and idle amounts separately.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer receiptCostConsistency(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_receipt_cost_consistency",
                """
                        The active-session cost is a backend-provided estimate. The completed receipt is the final billable record and must reconcile energy, time, session fee, taxes, subscription discount, idle fee, and configured caps.

                        If the values differ, investigate the selected session's meter timestamps, tariff snapshot, tax/discount application, idle duration, and terminal settlement event. Do not calculate or correct the total in the UI.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer subscriptionLifecycle(String message, ContextPayload context) {
        if (containsAny(message, "discount not applied", "discount", "not applied")) {
            return new DiagnosticAnswer(
                    "explain_subscription_discount",
                    """
                            A subscription discount is applied by the backend only when the plan is active, its scope matches the selected network/location/tariff, and usable quota remains.

                            Check the selected session's subscription allocation, eligible energy, remaining quota, and receipt discount line. An ACTIVE plan with zero remaining quota is active administratively but cannot discount new energy.
                            """.trim(),
                    contextSummary(context)
            );
        }
        if (containsAny(message, "quota exhausted", "exhausted")) {
            return new DiagnosticAnswer(
                    "explain_subscription_exhaustion",
                    """
                            When subscription quota is exhausted, later eligible energy is charged at the normal applicable tariff. The plan can remain ACTIVE for its validity period while its remaining quota is zero.

                            The receipt must show covered versus uncovered energy and any discount actually applied. Do not show a discount for energy after quota exhaustion.
                            """.trim(),
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "explain_subscription_quota",
                """
                        Subscription quota is consumed by eligible energy under the plan's configured scope. The backend records covered and uncovered energy atomically so the receipt and remaining quota stay consistent.

                        Check the plan allocation, scope, remaining quota, and the selected receipt before changing a subscription configuration.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer notificationLifecycle(String message, ContextPayload context) {
        if (containsAny(message, "push delivery", "delivery fails", "push fail")) {
            return new DiagnosticAnswer(
                    "explain_push_delivery_failure",
                    """
                            A push-delivery failure must not discard the notification record. The backend should retain its delivery state, retry transient Firebase failures with backoff, and move permanently failed deliveries to an auditable dead-letter or failed state.

                            Check device registration, Firebase project credentials, quota, and the notification dispatch result. The in-app notification can still be shown when push delivery fails.
                            """.trim(),
                    contextSummary(context)
            );
        }
        return new DiagnosticAnswer(
                "explain_notification_generation",
                """
                        A notification is generated only after the backend confirms the matching domain event and recipient. Open the notification record to see its event type, session or account reference, timestamp, and delivery state.

                        The app must not create a notification solely from a transient UI or SSE update. Repeated events for the same business transition must be deduplicated.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer rbacScope(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_rbac_scope",
                """
                        Administrative access is enforced by server-derived role and data scope, not by a browser filter. A location administrator may manage that location's chargers, connectors, sessions, and scoped dashboard, while parent enterprise and network details are read-only.

                        A Forbidden response means the role or assigned enterprise/network/location scope does not permit that operation. It must not expose records from another operator while diagnosing the access issue.
                        """.trim(),
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

    private DiagnosticAnswer chargerAlternatives(String userMessage, ContextPayload context, IdentityContext identity) {
        BackendDiagnosticsClient.ChargerAlternatives alternatives =
                diagnosticsClient.findChargerAlternatives(context, userMessage, 3, identity);
        if (alternatives == null) {
            alternatives = diagnosticsClient.findChargerAlternatives(context, userMessage, 3);
        }
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

    private DiagnosticAnswer rfidAuthorization(ContextPayload context) {
        return new DiagnosticAnswer(
                "authorize_rfid",
                """
                        An RFID tag must be authorized before charging can start. An unknown or unauthorized tag must be rejected and must not create a transaction or charging session.

                        Check the RFID authorization record, then tap again only after the tag is active for the selected network or charger.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer plugAndChargeAuthorization(ContextPayload context) {
        return new DiagnosticAnswer(
                "authorize_plug_and_charge",
                """
                        Plug and Charge must validate the EMAID and contract certificate before charging starts. If the certificate is invalid, expired, or untrusted, authorization must be rejected and no session may start.

                        Verify the contract certificate chain and EMAID registration for this charger, then retry authorization.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer paymentAuthorization(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_payment_authorization",
                """
                        For an account-linked credit-card session, ElectraHub should authorize the configured payment hold before remote start. If the charger rejects remote start or does not confirm the session, the unused authorization must be voided or reversed promptly.

                        On a completed session, capture only the final billable amount and release any unused hold. A refund is a separate, auditable operation after a completed capture.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer realTimeCost(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_real_time_cost",
                """
                        Real-time charging cost is calculated by the backend, not by the app. The backend applies energy and time charges, session fee, taxes, subscription discount, idle fee, and the configured session and idle-fee caps.

                        The app should display the active-session values returned by the backend, and the completed receipt should show the itemized final amount.
                        """.trim(),
                contextSummary(context)
        );
    }

    private DiagnosticAnswer chargingNotifications(ContextPayload context) {
        return new DiagnosticAnswer(
                "explain_charging_notifications",
                """
                        Charging notifications are generated only after the backend confirms the matching session state. Idle alerts require an actual idle/SUSPENDED session, and battery-full alerts require the matching backend event.

                        Repeated OCPP or SSE updates must not create duplicate notifications. Open the active session or notification record to verify the event and timestamp.
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
        String revenue = contextAttribute(context, "totalRevenue");
        if (!isBlank(revenue)) {
            String currency = optionalContextAttribute(context, "currency", "USD");
            String from = optionalContextAttribute(context, "from", "selected start");
            String to = optionalContextAttribute(context, "to", "selected end");
            String sessions = contextAttribute(context, "totalSessions");
            String location = optionalContextAttribute(context, "filterLocationId", "all locations");
            String formattedRevenue = formatMetric(revenue, 2);
            String sessionLine = isBlank(sessions) ? "" : " It includes " + formatMetric(sessions, 0) + " completed session(s).";
            return new DiagnosticAnswer(
                    "explain_admin_total_revenue",
                    "Total revenue for the current dashboard filter is " + currency + " " + formattedRevenue + ".\n\n"
                            + "Period: " + from + " to " + to + ". Location filter: " + location + "." + sessionLine
                            + "\n\nThis value comes from the live analytics response currently displayed on the dashboard.",
                    "live dashboard analytics"
            );
        }
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

    private static String contextAttribute(ContextPayload context, String key) {
        if (context == null || context.attributes() == null) return "";
        String value = context.attributes().get(key);
        return value == null ? "" : value.trim();
    }

    private static String optionalContextAttribute(ContextPayload context, String key, String fallback) {
        String value = contextAttribute(context, key);
        return isBlank(value) ? fallback : value;
    }

    private static String formatMetric(String value, int fractionDigits) {
        try {
            NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
            format.setMinimumFractionDigits(fractionDigits);
            format.setMaximumFractionDigits(fractionDigits);
            return format.format(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
            return value;
        }
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

    private DiagnosticAnswer adminScreenGuidance(String message, ContextPayload context) {
        String screen = normalize(firstNonBlank(context == null ? null : context.screen(),
                context == null ? null : context.resourceType()));
        String guidance = switch (screen) {
            case "users", "user" -> "Review account status, assigned role, tenant scope, verification state, and recent access activity. Confirm the user belongs to the expected operator before changing access or account state.";
            case "admin-users", "admin-user" -> "Review the administrator's role, assigned enterprise/network/location scope, account status, and recent audit activity. Apply least privilege and verify the target scope before granting or changing access.";
            case "charger-enterprise", "enterprise" -> "Review enterprise status, owning operator, assigned networks, locations, charger totals, and any records outside the expected tenant scope. Confirm dependencies before disabling or changing ownership.";
            case "charger-network", "network" -> "Review network status, owning enterprise/operator, assigned locations, charger availability, active sessions, and recent faults. Confirm scope and downstream impact before changing the network.";
            case "charger-location", "location" -> "Review the location's network/enterprise assignment, address and timezone, charger/connector availability, pricing and tax policies, and active sessions. Resolve missing configuration before publishing or disabling the location.";
            case "charger-groups", "charger-group" -> "Review group membership, location/network scope, charger status, pricing assignment, and active sessions. Verify that bulk changes affect only the intended chargers.";
            case "evses", "evse" -> "Review EVSE identity, parent charger/location, connector inventory, OCPP status, power capability, and active or reserved sessions. Reconcile stale state with the latest charger event before editing.";
            case "audit-logs", "audit-log" -> "Filter by actor, action, resource, tenant scope, outcome, and time range. Investigate failed or high-risk changes by correlating the audit entry with the affected resource and request identifier.";
            case "tax-configuration", "tax-policy" -> taxGuidance(message);
            case "allocations", "allocation" -> allocationGuidance(message);
            case "utilizations", "subscription-utilization" -> utilizationGuidance(message);
            case "network-operator" -> "Review operator status, tenant identity, assigned enterprises/networks, administrative users, payment configuration, and recent audit activity. Verify isolation boundaries before changing ownership or access.";
            case "charge-station-make" -> "Review manufacturer identity, supported models, connector standards, and chargers using this make. Do not remove or rename it until dependent models and chargers are checked.";
            case "charge-station-model" -> "Review manufacturer, power and connector capabilities, protocol compatibility, and deployed chargers. Validate compatibility before changing model defaults.";
            case "port-level" -> "Review connector format, standard, power type, maximum power, and model dependencies. Confirm existing chargers will remain valid before changing the port definition.";
            case "site-controller" -> "Review controller connectivity, assigned location/chargers, last heartbeat, software state, and active sessions. Confirm failover and charger impact before disabling or reassigning it.";
            case "subscriptions", "subscription" -> "Review plan status, eligibility scope, quota and consumption rules, discount, validity dates, and current allocations. Confirm active subscribers and billing impact before changing the plan.";
            case "rbac-policy" -> "Review the role's allowed operations and assigned enterprise, network, and location scope. Test both permitted access and cross-tenant denial before publishing a policy change.";
            case "notifications", "notification" -> "Review the source event, session/resource identifier, notification type, deduplication key, delivery attempts, and final delivery state. Repeated backend events must not create duplicate alerts.";
            case "pricing", "tariff" -> "Review tariff scope, currency, effective dates, time-of-use periods, energy/time/session/idle fees, caps, and overlapping assignments. Validate the resolved current price before activation.";
            case "charging-sessions", "session" -> "Review selected session state, charger/connector state, latest OCPP event, authorization/payment state, meter values, pricing, and settlement. Do not mutate a session without confirming its current terminal or active state.";
            case "refunds", "refund" -> "Review the captured payment, refundable balance, refund status, gateway reference, reason, actor, and audit history. Verify idempotency and the original transaction before approving or retrying a refund.";
            case "payment-gateways", "payment-gateway" -> "Review gateway status, supported currencies and methods, routing priority, webhook health, credential rotation state, and recent authorization or capture failures. Never expose secret values and validate failover before changing routing.";
            case "mobility-contracts", "mobility-contract" -> "Review contract status, tenant and e-mobility provider scope, identifiers, validity, certificate linkage, authorization rules, and recent Plug & Charge activity. Confirm revocation and downstream authorization impact before changing the contract.";
            case "root-ca-ceremonies", "root-ca-ceremony" -> "Review ceremony status, authorized participants, quorum, certificate chain, key custody evidence, timestamps, and audit records. Follow the approved ceremony runbook and never expose private key material.";
            case "chargers", "charger", "connectors", "connector" -> "Review live heartbeat, latest OCPP status, connector availability, active/reserved sessions, location assignment, and pricing. Reconcile stale state before an operational change.";
            case "dashboard" -> "Review scoped failed starts, offline or faulted chargers, stuck or idle sessions, payment/settlement failures, and unread operational notifications. Use the selected date and tenant scope before drawing conclusions.";
            default -> "Review the selected record's status, tenant scope, dependencies, recent audit activity, and active operational impact. Select a record for a precise diagnosis before making a change.";
        };
        return new DiagnosticAnswer("explain_admin_screen", guidance, contextSummary(context));
    }

    private static String taxGuidance(String message) {
        if (containsAny(message, "inheritance", "inherit", "scope")) {
            return "Resolve tax policy from the most specific effective scope: location override, then network, enterprise, and operator default. Verify currency, jurisdiction, effective dates, and that overlapping policies do not create ambiguity.";
        }
        return "Review locations without an effective tax policy, overlapping effective dates, jurisdiction/currency mismatches, inheritance source, and draft policies awaiting activation. Validate the resolved tax for affected locations before activation.";
    }

    private static String allocationGuidance(String message) {
        return "Review allocation status, subscriber and plan scope, validity dates, granted quota, consumed and remaining quota, and exhausted or overlapping allocations. Reconcile unexpected usage with completed eligible sessions before changing quota.";
    }

    private static String utilizationGuidance(String message) {
        return "Review the subscription allocation, eligible completed sessions, metered energy, discount applied, atomic quota deductions, remaining quota, and date/scope filters. Investigate duplicate session accounting or mismatched plan scope before correcting usage.";
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

    private static boolean isRfidAuthorizationQuestion(String message) {
        return containsAny(message, "rfid", "id tag")
                && containsAny(message, "authorize", "authorization", "unknown", "unauthorized", "reject", "start");
    }

    private static boolean isPastSessionDiagnosisQuestion(String message) {
        return containsAny(message, "last charge", "last session", "previous charge", "previous session")
                && containsAny(message, "fail", "failed", "stop", "stopped", "end", "ended", "receipt");
    }

    private static boolean isPlugAndChargeQuestion(String message) {
        return containsAny(message, "plug and charge", "plug-and-charge", "pnc", "emaid", "contract certificate")
                && containsAny(message, "certificate", "authorize", "authorization", "fail", "start", "validate");
    }

    private static boolean isPaymentAuthorizationQuestion(String message) {
        return containsAny(message, "credit card", "credit-card", "payment")
                && containsAny(message, "hold", "preauth", "pre-authorization", "authorization", "authorize", "void", "reverse", "refund", "capture");
    }

    private static boolean isRealTimeCostQuestion(String message) {
        return containsAny(message, "real time cost", "realtime cost", "live cost", "cost calculation", "billing calculation")
                || (containsAny(message, "idle fee", "session cap", "idle cap")
                && containsAny(message, "cost", "calculation", "calculate", "incorrect"));
    }

    private static boolean isFeeCapQuestion(String message) {
        return containsAny(message, "idle-fee", "idle fee", "session cap", "idle cap")
                && containsAny(message, "cap", "work", "limit", "maximum");
    }

    private static boolean isReceiptCostConsistencyQuestion(String message) {
        return containsAny(message, "receipt total", "receipt totals", "receipt cost")
                && containsAny(message, "active-session", "active session", "match", "different", "incorrect");
    }

    private static boolean isSubscriptionQuestion(String message) {
        return containsAny(message, "subscription", "quota")
                && containsAny(message, "discount", "consumed", "consume", "exhausted", "quota", "applied");
    }

    private static boolean isChargingNotificationQuestion(String message) {
        return containsAny(message, "notification", "notifications", "alert", "alerts", "push alert",
                "push notification", "battery full")
                && containsAny(message, "idle", "battery", "duplicate", "duplicates", "deduplicate",
                "deduplication", "repeated", "charging", "session");
    }

    private static boolean isNotificationLifecycleQuestion(String message) {
        return containsAny(message, "notification", "push delivery", "push fail")
                && containsAny(message, "generated", "delivery", "fail", "failed", "prevented", "why");
    }

    private static boolean isRemoteStopIdleQuestion(String message) {
        return containsAny(message, "remote stop", "stop charging", "stop request")
                && containsAny(message, "idle", "idle fee", "receipt", "unplug", "still active");
    }

    private static boolean isRemoteStopPreparationQuestion(String message) {
        return containsAny(message, "remote stop", "remotely stopping")
                && containsAny(message, "before", "check", "prepare");
    }

    private static boolean isChargerStatusManagementQuestion(String message) {
        return containsAny(message, "changing charger status", "change charger status", "mark charger", "inoperative")
                && containsAny(message, "before", "check", "status", "charger");
    }

    private static boolean isExplicitAvailableQuestion(String message) {
        return containsAny(message, "explicit available", "available status")
                && containsAny(message, "status", "happen", "transaction", "unplug");
    }

    private static boolean isRbacQuestion(String message) {
        return containsAny(message, "location administrator", "location admin", "enterprise and network data scopes",
                "data scope", "administrator receiving forbidden", "access scope")
                || (message.contains("forbidden") && containsAny(message, "administrator", "admin", "access", "role"));
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

    private static boolean isDashboardAttentionQuestion(String message, ContextPayload context) {
        String screen = context == null || context.screen() == null ? "" : context.screen().toLowerCase();
        return screen.contains("dashboard")
                && containsAny(message, "needs attention", "attention on this dashboard", "what should i monitor")
                && !containsAny(message, "charging success", "success rate", "csr");
    }

    private static boolean isChargingSuccessQuestion(String message, ContextPayload context) {
        String screen = context == null || context.screen() == null ? "" : context.screen().toLowerCase();
        return containsAny(message, "charging success", "success rate", "csr")
                && (screen.contains("dashboard") || message.contains("monitor"));
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

    private static boolean isChargeExplanationQuestion(String message) {
        return containsAny(message, "why was i charged", "why did i get charged", "explain this charge",
                "charged amount", "charge breakdown");
    }

    private static boolean isIdleFeeExplanationQuestion(String message) {
        return containsAny(message, "idle fee", "idle-fee")
                && containsAny(message, "how does", "how do", "explain", "work", "calculated", "calculation");
    }

    private static boolean isConnectorStatusMeaningQuestion(String message) {
        return containsAny(message, "what does this status mean", "what does the status mean",
                "explain this status", "connector status meaning", "charger status meaning");
    }

    private static boolean isFindChargerQuestion(String message) {
        return containsAny(message, "find another", "find a charger", "another ccs", "nearby charger", "search charger");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isAdministrativeAudience(ContextPayload context) {
        String audience = normalize(context == null ? null : context.audience());
        return containsAny(audience, "admin", "support", "csr");
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static boolean requiresSelectedRecord(ContextPayload context) {
        if (context == null || context.attributes() == null
                || !"SELECTED_RECORD".equals(context.attributes().get("responseMode"))) {
            return false;
        }
        return isBlank(context.resourceId()) && isBlank(context.chargerId()) && isBlank(context.connectorId())
                && isBlank(context.locationId()) && isBlank(context.sessionId());
    }

    private static DiagnosticAnswer selectedRecordRequired(ContextPayload context) {
        String resource = isBlank(context.resourceType()) ? "record" : context.resourceType().replace('-', ' ');
        return new DiagnosticAnswer(
                "admin.selected-record.required",
                "Select a " + resource + " record first so I can answer using its verified, role-scoped details.",
                "selected " + resource + " required");
    }

    private Optional<DiagnosticAnswer> answerAdminCommand(String message,
                                                           ContextPayload context,
                                                           String authorization,
                                                           IdentityContext identity) {
        return adminCommandService == null
                ? Optional.empty()
                : adminCommandService.answer(message, context, authorization, identity);
    }

    private static IdentityContext legacyIdentity(String authorization) {
        boolean authenticated = authorization != null && !authorization.isBlank();
        return new IdentityContext(
                authenticated ? "electrahub" : "public",
                authenticated ? "legacy-user" : "anonymous",
                Set.of(),
                authenticated);
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

    public record DiagnosticAnswer(String toolName, String text, String contextSummary) {
    }

    private record ChargerAvailability(boolean available,
                                       int availablePorts,
                                       int busyPorts,
                                       Optional<String> connectorStatus) {
    }
}
