# Sparky analysis and private MCP integration

Implemented October 5, 2026 in the existing local checkouts. Application changes are not deployed. This extends the existing session diagnostics aggregate; it does not create a new model or train model weights.

## Access and request flow

Only authenticated `SYSTEM_ADMIN` and `SUPPORT` identities may use selected-session analysis. UI gating is backed by independent AI-service, gateway, session-API and MCP authorization. `ADMIN_READ_ONLY`, `TENANT_ADMIN`, scoped administrator roles and drivers do not gain analysis access by choosing an audience, naming a role or changing prompt text. Scoped users retain general knowledge chat and unrelated authorized selected-record guidance. Existing session read grants still apply to Support; the role alone grants no organization ownership or global access.

1. The portal selects the session; the AI host validates trusted identity and tenant tool policy before any retrieval or model work.
2. With MCP enabled, the host negotiates pinned protocol `2025-11-25` and sends the current signed identity plus original bearer for each request. The configured private URL is fixed; model output cannot change it.
3. `get_session_evidence` always runs first. Its response must match the selected UUID, have valid bounded facts/gaps and have a collection timestamp within two minutes. Failures produce an explicit unavailable report; the host does not silently switch transport.
4. An optional, bounded model planning turn selects up to three context categories. The host fixes all arguments: selected session ID, `charging-session` flow and no topology selector. Unknown tools, extra fields, duplicates or malformed output fall back to the fixed read plan. The flow reference is mandatory when permitted by policy.
5. MCP reads organization context through the authorized session API. Cluster topology and repository flow definitions come from local versioned memory with explicit provenance/freshness. They are operational context, never proof of customer events or current ownership.
6. The final model turn returns only `findingIndexes` and `nextCheck`. The host renders selected facts verbatim and a predefined read-only check. Invalid prose, invented amounts, unknown actions and bad indexes fall back to the deterministic report. Every result retains the full evidence and unavailable checks. No stop, refund, release, retry or billing correction is executed.

General `KNOWLEDGE` and `CHANGE_PRECHECK` messages issue no live diagnostic reads. Session-specific analysis never reads the support agent's own wallet or active driver sessions. Stored analysis replay requires the current request to retain an authorized role; the UI clears privileged messages and pending responses when identity, token, roles or selection changes. Already-open streams use their resolved request identity; continuous mid-stream revocation polling is not implemented.

## Configuration and rollout

| Component | Setting | Purpose |
| --- | --- | --- |
| AI host | `AI_SUPPORT_MCP_ENABLED=true` | Route support investigation through the deployed private MCP service; default false permits staged rollout |
| AI host | `AI_SUPPORT_MCP_URL=http://support-mcp-service:8095/mcp` | Fixed private endpoint, default shown |
| AI host | `AI_SUPPORT_MCP_MODEL_PLANNING_ENABLED=true` | Optional context-category selection; default true, bounded fallback on outage |
| All identity verifiers/signers | `APP_INTERNAL_ACCESS_CONTEXT_SECRET` | Provision the same non-default secret; MCP requires at least 32 characters |
| MCP service | `SUPPORT_MCP_CLUSTER_ENABLED=true` | Explicitly enable the read-only cluster collector after configuring scope/network access |
| MCP service | `SUPPORT_MCP_CLUSTER_ID`, `SUPPORT_MCP_CLUSTER_NAMESPACES` | Stable cluster identity and explicit namespace scope |
| MCP service | `SUPPORT_MCP_MEMORY_DIRECTORY` | Writable durable infrastructure cache; never customer/session memory |

The existing `admin.sessions.diagnose` tenant permission is required. Restricted tenant policies additionally opt into `support.context.plan`, `support.context.get_flow_definition`, `support.context.get_org_context` and `support.context.get_service_topology`. Wildcard policies allow these; denied context categories are reported as gaps and do not prevent the authorized session report. Jev remains optional and disabled unless its existing explicit flag/key/policy are configured.

Build and release the session/domain authorization changes, gateway policy and user-service `0043` migration before exposing the UI. Deploy the new MCP image privately with its provisioned identity secret and volume, then enable the AI host flag. The [deployment example](../support-mcp-service/deploy/kubernetes-example.yaml) requires the actual image digest, namespace/workload labels, storage settings and API-server destination CIDR before applying. It is an example, not an applied production manifest. Validate permitted/denied sessions and roles in staging. Disable `AI_SUPPORT_MCP_ENABLED` to return to the scoped direct report while retaining role enforcement; disable the collector separately to stop cluster reads.

## Budgets and memory

The host enforces a 20-second evidence/context budget and a separate 12-second final support-model budget, including provider-chain time. Planning is capped at 2.5 seconds. Every MCP exchange has a whole-response deadline, cancellation, a 256 KiB streamed byte cap and no redirects. Model context has separate fact/reference limits; omitted prompt content remains visible in the full report. Authorized analysis transport permits 60 seconds; ordinary chat remains at 30 seconds. The local Ollama support selection uses a 16k context window; validate runtime memory/capacity before rollout.

MCP stores only bounded sanitized infrastructure snapshots and packaged reviewed knowledge. All 29 repository identities/versions are in the catalog; only approved semantic tools can invoke APIs. The collector performs bounded scheduled reconciliation, not Kubernetes watches. The example runs one writer per persistent volume. Multi-replica shared memory, automatic repository/vector ingestion and fine-tuning remain outside this implementation. See the [service README](../support-mcp-service/README.md) for cache expiry, resource limits and collector behavior.

## Prompt and model evaluation

`SupportPrompt`, `LlmPromptFormatter` and `ollama/Modelfile` now define the support context planner, lifecycle distinctions, source authority and structured evidence selection. Existing provider configuration is retained. Updating the Modelfile requires recreating the deployed Ollama model with `scripts/ollama/create-electrahub-sparky.ps1`; no running model was changed here.

Ten synthetic cases in `scripts/ollama/sparky-support-evaluation.json` cover acknowledgement versus charging, hold versus capture, meter rows versus messages, superseded settlement failures, PREPARING, connector-window correlation, subscription snapshots, stale topology, injected instructions and organization ownership. Run `python scripts/ollama/evaluate-support-analysis.py --model <candidate> --report <output.json>` against a locally hosted candidate model. `--validate-only` validates fixtures without inference and is not a model-quality result. The local fixture check passed; no live model evaluation or weight fine-tuning was performed.

Implementation validation includes the full AI/gateway/UI suites, domain role/scope/report tests, MCP protocol/auth/cache/transport tests, and an opt-in `SupportMcpLiveContractTest` against the built MCP jar with a synthetic local gateway. The integration confirmed session evidence, flow/repository provenance, organization context and disabled-cluster gaps across the actual host/server transport; temporary servers were stopped afterward. UI TypeScript checks pass. No live cluster credentials, customer records, database migration application or deployment were used for these checks.

Design references: [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling) and [evaluation guidance](https://developers.openai.com/api/docs/guides/evaluation-best-practices). The host validates model choices and executes tools; the model never receives service credentials.
