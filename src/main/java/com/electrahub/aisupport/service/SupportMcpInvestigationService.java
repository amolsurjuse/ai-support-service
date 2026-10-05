package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** One bounded model planning turn, allowlisted MCP reads, then the normal grounded answer turn. */
@Service
public class SupportMcpInvestigationService {
    static final List<String> CONTEXT_TOOLS = List.of("get_flow_definition", "get_org_context", "get_service_topology");
    private final SupportMcpClient mcp;
    private final AiToolAuthorizationService access;
    private final TenantAiPolicyService policies;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final PiiRedactor redactor;
    private final boolean planningEnabled;
    private final ExecutorService planner = Executors.newVirtualThreadPerTaskExecutor();

    public SupportMcpInvestigationService(SupportMcpClient mcp, AiToolAuthorizationService access,
            TenantAiPolicyService policies, LlmClient llm, ObjectMapper mapper, PiiRedactor redactor,
            @Value("${electrahub.ai-support.mcp.model-planning-enabled:true}") boolean planningEnabled) {
        this.mcp = mcp;
        this.access = access;
        this.policies = policies;
        this.llm = llm;
        this.mapper = mapper;
        this.redactor = redactor;
        this.planningEnabled = planningEnabled;
    }

    boolean enabled() { return mcp.enabled(); }

    BackendDiagnosticsClient.DiagnosticsSnapshot collect(String question, ContextPayload context,
                                                          String bearer, IdentityContext identity) {
        access.requireSupportAnalysis(identity);
        var policy = policies.policyFor(identity.tenantId());
        if (!policy.enabled() || !policy.allowsTool("admin.sessions.diagnose")) {
            return gap("Selected-session investigation is not permitted by your tenant policy.");
        }
        UUID sessionId;
        try { sessionId = UUID.fromString(context.sessionId()); }
        catch (RuntimeException ex) { return gap("Select a valid charging session before running analysis."); }
        List<String> facts = new ArrayList<>(), gaps = new ArrayList<>(), references = new ArrayList<>();
        Map<String, String> selected = Map.of("sessionId", sessionId.toString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            mcp.initialize(identity, bearer, remaining(deadline, 1500));
            JsonNode report = mcp.call("get_session_evidence", selected, identity, bearer, remaining(deadline, 8500));
            // A mismatch/denial never falls back to a less restricted transport.
            if (!sessionId.toString().equalsIgnoreCase(report.path("sessionId").asText())
                    || !isTextArray(report.path("facts")) || !isTextArray(report.path("gaps"))) {
                return gap("MCP evidence was incomplete or did not match the selected session. No diagnosis is confirmed.");
            }
            Instant collectedAt = Instant.parse(report.path("collectedAt").asText());
            if (collectedAt.isAfter(Instant.now().plusSeconds(30)) || collectedAt.isBefore(Instant.now().minusSeconds(120))) {
                return gap("MCP session evidence was stale or had an invalid collection time. Retry the scoped investigation.");
            }
            facts.add("Evidence collected at " + collectedAt + " for selected session " + sessionId);
            report.path("facts").forEach(value -> facts.add(value.asText()));
            report.path("gaps").forEach(value -> gaps.add(value.asText()));
        } catch (RuntimeException ex) {
            return gap("Private MCP session evidence is unavailable or access was denied. No failure cause is confirmed; retry the scoped investigation.");
        }

        // A model may select context categories. It can never supply resource IDs, routes, credentials or operations.
        for (String tool : plan(question, identity, deadline)) {
            if (!policy.allowsTool("support.context." + tool)) {
                gaps.add("Context tool " + tool + " is disabled by tenant policy.");
                continue;
            }
            Map<String, String> args = switch (tool) {
                case "get_org_context" -> selected;
                case "get_flow_definition" -> Map.of("flowId", "charging-session");
                default -> Map.of();
            };
            try {
                JsonNode data = mcp.call(tool, args, identity, bearer, remaining(deadline, 2500));
                if ("get_org_context".equals(tool)
                        && !sessionId.toString().equalsIgnoreCase(data.path("sessionId").asText())) {
                    throw new IllegalStateException("Organization context does not match session");
                }
                String content = mapper.writeValueAsString(data);
                if (content.length() > 24000) {
                    gaps.add(tool + " exceeded the support context budget; its result was not used.");
                } else {
                    references.add(tool + ": " + content);
                }
            } catch (RuntimeException ex) {
                gaps.add(tool + " was unavailable, denied, or exceeded the investigation time budget.");
            }
        }
        return new BackendDiagnosticsClient.DiagnosticsSnapshot(List.copyOf(facts), List.copyOf(gaps), List.copyOf(references));
    }

    private List<String> plan(String question, IdentityContext identity, long deadline) {
        if (!planningEnabled || !llm.available() || !policies.policyFor(identity.tenantId()).allowsTool("support.context.plan")) {
            return CONTEXT_TOOLS;
        }
        Future<LlmClient.LlmCompletion> task = planner.submit(() -> llm.complete(new LlmClient.LlmPrompt(
                redactor.redactForHostedProvider(question), null,
                new DiagnosticAnswerService.DiagnosticAnswer("plan_support_context", "Select relevant context categories only.", ""),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of()))));
        try {
            var response = task.get(remaining(deadline, 2500).toMillis(), TimeUnit.MILLISECONDS);
            if (response != null && response.ok()) return parsePlan(response.answer());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            // The deterministic read plan remains available during provider outages or malformed model output.
        } finally {
            task.cancel(true);
        }
        return CONTEXT_TOOLS;
    }

    List<String> parsePlan(String value) {
        if (value == null || value.length() > 1024) return CONTEXT_TOOLS;
        try {
            JsonNode root = mapper.readTree(value);
            if (!root.isObject() || root.size() != 1 || !root.path("tools").isArray()
                    || root.path("tools").size() > CONTEXT_TOOLS.size()) return CONTEXT_TOOLS;
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (JsonNode tool : root.path("tools")) {
                if (!tool.isTextual() || !CONTEXT_TOOLS.contains(tool.asText()) || !names.add(tool.asText())) return CONTEXT_TOOLS;
            }
            // The lifecycle definition is always present to ground the final diagnostic interpretation.
            names.add("get_flow_definition");
            return List.copyOf(names);
        } catch (RuntimeException ex) { return CONTEXT_TOOLS; }
    }

    private static boolean isTextArray(JsonNode value) {
        if (!value.isArray() || value.size() > 512) return false;
        for (JsonNode item : value) if (!item.isTextual() || item.asText().length() > 8192) return false;
        return true;
    }

    private static Duration remaining(long deadline, int maxMs) {
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left <= 0) throw new IllegalStateException("Investigation time budget exhausted");
        return Duration.ofMillis(Math.min(left, maxMs));
    }

    private static BackendDiagnosticsClient.DiagnosticsSnapshot gap(String message) {
        return new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of(message));
    }

    @PreDestroy
    void close() { planner.shutdownNow(); }
}
