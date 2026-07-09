package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ElectraHubKnowledgeBase {
    private static final List<KnowledgeRule> RULES = List.of(
            new KnowledgeRule(
                    "remote stop and idle fee",
                    "After a remote stop on an idle-fee charger, the session should move to SUSPENDED/idle and stay active until the EV is unplugged. Receipt generation should wait for unplug or terminal settlement.",
                    "remote stop", "stop charging", "idle", "idle fee", "receipt", "suspended", "unplug"),
            new KnowledgeRule(
                    "simulator secure unplug",
                    "Simulator unplug requires the active session security code when idle fee is enabled. Mobile app simulator links can include the code so the user can unplug without typing it. If idle fee is disabled or no active session exists, unplug should not require a security code.",
                    "simulator", "security code", "unplug", "hmi", "iframe", "charger link"),
            new KnowledgeRule(
                    "active charging screen",
                    "The mobile active charging and idle screens should show the simulator link, selected charger/connector, and security code when unplug is required. The link should open the connector HMI screen, not a download prompt or connector-list side panel.",
                    "active charging", "live charging", "mobile app", "ios", "android", "security code", "simulator link"),
            new KnowledgeRule(
                    "charger status flow",
                    "Connector state decisions should use Redis connector/session state plus OCPP StatusNotification and active session state. The simulator must not emit a transaction id on an explicit Available status after unplug.",
                    "status notification", "available", "redis", "connector", "transaction id", "ocpp"),
            new KnowledgeRule(
                    "pricing and idle fee",
                    "Idle fee comes from price plan/tariff data and should be consistent across charger details, active charging, idle screen, receipt, and admin views. Receipts should separate energy cost, idle fee, taxes, subscription discount, and total.",
                    "price", "pricing", "tariff", "idle fee", "receipt", "subscription", "discount", "tax"),
            new KnowledgeRule(
                    "admin revenue dashboard",
                    "Admin dashboard total revenue should be based on completed charging sessions for the selected date filter and should align with receipts. Revenue comparisons should use the previous equivalent date window and the same completed-session timestamp and billable amount fields.",
                    "dashboard", "total revenue", "revenue", "sales", "income", "percentage", "filter"),
            new KnowledgeRule(
                    "wallet threshold and auto top-up",
                    "During charging, backend should stop when post-subscription cost plus taxes and fees would breach the low-balance/session cap. If auto top-up is enabled and a valid card exists, wallet top-up should happen before low-balance stop.",
                    "wallet", "balance", "low balance", "auto top", "auto top-up", "session cap", "payment"),
            new KnowledgeRule(
                    "card present session",
                    "Tap-credit-card/card-present sessions can be anonymous because the user may not be recognized. Show payment as Credit Card with a masked card number when available; for ElectraHub-authorized card-present flows show the ElectraHub payment authorization id instead of raw processor transaction ids.",
                    "card present", "credit card", "tap credit", "payment method", "masked", "anonymous", "transaction id"),
            new KnowledgeRule(
                    "admin charging sessions",
                    "Admin portal charging sessions should support active and completed tabs, filters by location/charger/driver/payment/auth method, receipt view for completed sessions, and remote stop for active sessions.",
                    "admin", "session tab", "charging sessions", "filter", "receipt", "remote stop"),
            new KnowledgeRule(
                    "project services",
                    "Relevant ElectraHub services include session-service for charging session state, ocpp-service for charger WebSocket/OCPP messages, ocpi-service for OCPI data, charger-management-service/station-management-service for charger inventory, pricing-service for tariffs, payment-service for wallet/cards, subscription-service for discounts, notification-service for notifications, ai-support-service for Sparky.",
                    "service", "owner", "backend", "session-service", "ocpp-service", "pricing-service", "payment-service", "subscription-service"));

    private ElectraHubKnowledgeBase() {
    }

    static String relevantFacts(String userMessage, ContextPayload context) {
        String haystack = (nullToBlank(userMessage) + " "
                + nullToBlank(context == null ? null : context.screen()) + " "
                + nullToBlank(context == null ? null : context.resourceType()) + " "
                + nullToBlank(context == null ? null : context.audience())).toLowerCase(Locale.ROOT);

        List<String> facts = new ArrayList<>(RULES.stream()
                .map(rule -> new ScoredRule(rule, rule.score(haystack)))
                .filter(rule -> rule.score() > 0)
                .sorted(Comparator.comparingInt(ScoredRule::score).reversed())
                .limit(4)
                .map(rule -> rule.rule().fact())
                .toList());
        if (facts.isEmpty()) {
            facts.add("Use live backend facts first. If no live facts are available, give the safest next check and avoid inventing charger, wallet, payment, receipt, or session values.");
        }

        StringBuilder builder = new StringBuilder("Project knowledge:\n");
        for (String fact : facts) {
            builder.append("- ").append(fact).append('\n');
        }
        return builder.toString().trim();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private record KnowledgeRule(String name, String fact, String... keywords) {
        int score(String haystack) {
            int score = 0;
            for (String keyword : keywords) {
                if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            return score;
        }
    }

    private record ScoredRule(KnowledgeRule rule, int score) {
    }
}
