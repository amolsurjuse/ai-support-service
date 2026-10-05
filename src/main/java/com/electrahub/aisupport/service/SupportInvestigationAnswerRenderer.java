package com.electrahub.aisupport.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Model-selected evidence, server-rendered claims: free-form model facts never become a diagnosis. */
final class SupportInvestigationAnswerRenderer {
    static final Map<String, String> NEXT_CHECKS = Map.of(
            "START_CHAIN", "Compare start request, authorization, remote-start acknowledgement and physical-start timestamps in the selected session.",
            "AUTHORIZATION", "Check the recorded authorization amount, currency, status and release timestamps; confirm current processor status through the approved support workflow.",
            "METERING", "Compare the first and last meter timestamps with the charger telemetry cadence and transaction-correlated OCPP events.",
            "SUBSCRIPTION", "Compare the session's subscription allocation and consumed quota with its recorded discount and bill.",
            "BILLING", "Reconcile the recorded tariff, energy, idle fees, caps, subscription discount and tax with the backend bill; do not recalculate or change charges in chat.",
            "SETTLEMENT", "Check the latest recorded settlement attempt and capture evidence before recommending a retry or refund.",
            "OCPP_CORRELATION", "Check exact transaction-correlated OCPP events; connector-window events alone cannot establish what happened to this session.",
            "ORGANIZATION_SCOPE", "Verify the session's organization binding and your assigned support scope through the authorized application.",
            "SERVICE_HEALTH", "Check freshness and deployed revisions in the service context, then correlate the selected session with approved operational telemetry.",
            "EVIDENCE_GAPS", "Resolve the unavailable evidence checks listed below before confirming a failure cause or recommending an operational action.");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SupportInvestigationAnswerRenderer() {}

    static Optional<String> render(String candidate, BackendDiagnosticsClient.DiagnosticsSnapshot evidence) {
        if (candidate == null || candidate.length() > 2048 || evidence == null || evidence.facts().isEmpty()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(candidate);
            if (!root.isObject() || root.size() != 2 || !root.has("findingIndexes") || !root.has("nextCheck")
                    || !root.path("findingIndexes").isArray() || root.path("findingIndexes").isEmpty()
                    || root.path("findingIndexes").size() > 4 || !root.path("nextCheck").isTextual()
                    || !NEXT_CHECKS.containsKey(root.path("nextCheck").asText())) return Optional.empty();
            Set<Integer> indexes = new LinkedHashSet<>();
            for (JsonNode index : root.path("findingIndexes")) {
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.asInt() < 0
                        || index.asInt() >= evidence.facts().size() || !indexes.add(index.asInt())) return Optional.empty();
            }
            StringBuilder answer = new StringBuilder("Key recorded findings:\n");
            indexes.forEach(index -> answer.append("- ").append(evidence.facts().get(index)).append('\n'));
            answer.append("\nNext read-only check: ").append(NEXT_CHECKS.get(root.path("nextCheck").asText()));
            if (!evidence.gaps().isEmpty()) answer.append("\nSome evidence checks are unavailable; review the gaps in the report below.");
            return Optional.of(answer.toString());
        } catch (RuntimeException ex) { return Optional.empty(); }
    }
}
