# AI Support Service

ElectraHub Sparky support assistant for driver-facing iOS/Android chat and admin/CSR diagnostics.

## Current scope

- `POST /api/v1/chat/messages` creates or continues a chat thread.
- `GET /api/v1/chat/threads/{threadId}/stream?since={messageId}` streams an iOS-compatible SSE response.
- Driver mode is safe by default and does not expose engineer-only internals.
- Every message is evaluated once, cached for the short-lived SSE stream, and rendered consistently across iOS, Android, and the admin portal.
- Deterministic diagnostics remain the source of truth; the LLM improves clarity only after a grounding and safety quality check.

## Driver support contract

The iOS app sends:

```json
{
  "threadId": null,
  "content": "Why did start charging fail?",
  "context": {
    "screen": "liveCharging",
    "resourceType": "charging",
    "resourceId": null,
    "chargerId": "EH-US-CHG-0081",
    "connectorId": "CON-US-0081",
    "locationId": "US*EHB*LOC*USA017",
    "sessionId": null,
    "audience": "driver"
  }
}
```

The stream emits JSON `data:` payloads with these `type` values:

- `TOOL_CALL`
- `TOOL_RESULT`
- `TOKEN`
- `DONE`
- `ERROR`

## Next backend phases

1. Add read-only support tools for session, OCPP heartbeat, charger availability, wallet state, and logs.
2. Add LLM provider integration behind `AI_PROVIDER_ENABLED`.
3. Add Postgres persistence for threads/messages/audit.
4. Add gateway route `/ai/**` and k8s deployment through `k8s-platform`.

## LLM provider mode

Sparky can run as a real LLM-backed assistant while keeping deterministic diagnostics as a safe fallback.

Runtime settings:

```text
AI_PROVIDER_ENABLED=true
AI_PROVIDER=openai
OPENAI_API_KEY=<backend secret only>
OPENAI_BASE_URL=https://api.openai.com
AI_MODEL=gpt-4.1-mini
AI_TEMPERATURE=0.2
AI_MAX_OUTPUT_TOKENS=900
AI_LLM_TIMEOUT_MS=12000
```

The service sends only the redacted user message, screen/context identifiers, deterministic fallback answer, and summarized live backend facts to the LLM. It never sends bearer tokens or raw secrets to the provider. If the provider is disabled, unreachable, too slow, or returns an unsafe/unhelpful response, Sparky returns the deterministic diagnostic answer.

## Ollama provider mode

Sparky can also run against a local or cluster-hosted Ollama model. Ollama customization is handled with `ollama/Modelfile`, which creates an ElectraHub domain-tuned runtime model from a base model and system instructions.

The project tuning is intentionally grounded instead of open-ended fine-tuning:

- `ollama/Modelfile` defines Sparky's ElectraHub role, safety rules, service ownership, and terminology.
- `ElectraHubKnowledgeBase` injects a small set of request-relevant project facts into the prompt.
- Live backend diagnostics remain the source of truth for current charger, wallet, payment, receipt, and session state.
- If Ollama is unavailable or too slow, deterministic diagnostics are returned as the safe fallback.

The current production model is the non-thinking `qwen3:4b-instruct`, exposed as `electrahub-sparky:4b`. This keeps driver-facing latency predictable while the service still strips or rejects reasoning and prompt leakage before an answer can reach a client.

Create the local model:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\ollama\create-electrahub-sparky.ps1
```

Run the service against Ollama:

```text
AI_PROVIDER_ENABLED=true
AI_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
AI_MODEL=electrahub-sparky:4b
AI_TEMPERATURE=0.12
AI_MAX_OUTPUT_TOKENS=320
AI_LLM_TIMEOUT_MS=30000
AI_DIAGNOSTICS_TIMEOUT_MS=1800
AI_DIAGNOSTICS_TOTAL_TIMEOUT_MS=3000
AI_THREAD_TTL_MS=900000
```

Run the real-model quality suite after creating the model:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\ollama\evaluate-sparky-prompts.ps1 -FailOnQualityIssue
```

The suite covers every current iOS/admin suggested prompt plus critical simulator RFID, Plug and Charge, card-present, idle/unplug, and payment-authorisation scenarios. It checks that answers preserve required operational meaning and do not expose prompt content or hidden reasoning.

For Kubernetes, make the Ollama host reachable from `ai-support-service`, then set `AI_PROVIDER=ollama`, `OLLAMA_BASE_URL`, and `AI_MODEL=electrahub-sparky:4b`. The deterministic diagnostics remain the fallback if Ollama is unreachable or returns no usable answer.

`POST /api/v1/chat/messages` returns a normalized final answer for all clients. `GET /api/v1/chat/threads/{threadId}/stream?since={messageId}` streams the same rendered answer over SSE.
