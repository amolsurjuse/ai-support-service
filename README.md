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

## Provider routing and privacy

Sparky is grounded in live ElectraHub diagnostics. A model improves the wording of a response; it is never the source of truth for live charger, session, payment, subscription, or receipt state. If every configured provider is unavailable, too slow, unsafe, or unhelpful, the service returns the deterministic diagnostic answer.

The provider chain is local-first:

1. `vllm` serves Qwen3 8B when a GPU-backed vLLM deployment is enabled.
2. `ollama` serves the local ElectraHub-tuned Qwen3 4B model as the dependable fallback.
3. `gemini` is an optional, explicitly enabled hosted fallback using Gemini Flash-Lite.

The router applies a short circuit-breaker cooldown after a provider failure and does not queue more than the configured number of Ollama requests. This prevents a slow model from producing gateway timeouts for every concurrent Sparky request.

Hosted providers are opt-in. Before Gemini receives a prompt, the service removes emails, access tokens, UUIDs/session identifiers, payment-card-like values, phone numbers, and obvious secret assignments. Provider API keys are backend secrets and are never returned to the UI or logged.

### Common settings

Runtime settings:

```text
AI_PROVIDER_ENABLED=true
AI_PROVIDER_CHAIN=vllm,ollama,gemini
AI_TEMPERATURE=0.2
AI_MAX_OUTPUT_TOKENS=180
AI_PROVIDER_FAILURE_COOLDOWN_MS=30000
```

### GPU-backed Qwen3 8B through vLLM

`k8s-platform/infrastructure/vllm` provides an OpenAI-compatible vLLM deployment for `Qwen/Qwen3-8B`. It is deliberately disabled (`replicaCount: 0`) on clusters without an allocatable NVIDIA GPU. Do not enable it on CPU-only infrastructure: it will be slower and less stable than the local 4B fallback.

After assigning a GPU node with adequate VRAM and persistent model-cache storage, enable both the runtime and service route:

```text
AI_VLLM_ENABLED=true
VLLM_BASE_URL=http://vllm:8000
VLLM_MODEL=sparky-qwen3-8b
AI_VLLM_TIMEOUT_MS=7000
```

### Optional Gemini Flash-Lite fallback

Gemini is disabled by default. It may only be enabled after a backend-managed `GEMINI_API_KEY` is supplied through Kubernetes secret management and the data-processing decision has been approved.

```text
AI_GEMINI_ENABLED=true
AI_HOSTED_FALLBACK_ENABLED=true
GEMINI_MODEL=gemini-2.5-flash-lite
GEMINI_BASE_URL=https://generativelanguage.googleapis.com
AI_GEMINI_TIMEOUT_MS=7000
```

For a local-only deployment, keep both flags `false`. The router then uses the local vLLM/Ollama providers only.

## Local Ollama model

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

The current production fallback is intentionally retained because the current production cluster has no GPU and the 8B Ollama model previously caused overloaded-origin errors.

Run the service against Ollama:

```text
AI_PROVIDER_ENABLED=true
AI_PROVIDER=ollama
AI_PROVIDER_CHAIN=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=electrahub-sparky:4b
AI_OLLAMA_TIMEOUT_MS=14000
AI_OLLAMA_MAX_CONCURRENT_REQUESTS=1
AI_TEMPERATURE=0.12
AI_MAX_OUTPUT_TOKENS=180
AI_DIAGNOSTICS_TIMEOUT_MS=1800
AI_DIAGNOSTICS_TOTAL_TIMEOUT_MS=3000
AI_THREAD_TTL_MS=900000
```

Run the 41-case real-model quality suite after creating or changing a model:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\ollama\evaluate-sparky-prompts.ps1 -MaxOutputTokens 180 -FailOnQualityIssue
```

The same suite can validate a GPU vLLM deployment or an explicitly approved Gemini provider:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\ollama\evaluate-sparky-prompts.ps1 -Provider Vllm -BaseUrl http://localhost:8000 -FailOnQualityIssue
powershell -ExecutionPolicy Bypass -File .\scripts\ollama\evaluate-sparky-prompts.ps1 -Provider Gemini -BaseUrl https://generativelanguage.googleapis.com -ApiKey $env:GEMINI_API_KEY -FailOnQualityIssue
```

The suite covers every current iOS/admin suggested prompt plus critical simulator RFID, Plug and Charge, card-present, idle/unplug, payment-authorisation, notifications, pricing, subscriptions, and RBAC scenarios. It checks that answers preserve required operational meaning and do not expose prompt content or hidden reasoning.

For Kubernetes, make the Ollama host reachable from `ai-support-service`, then set `AI_PROVIDER_CHAIN`, `OLLAMA_BASE_URL`, and `OLLAMA_MODEL`. The deterministic diagnostics remain the fallback if no provider returns a usable answer.

`POST /api/v1/chat/messages` returns a normalized final answer for all clients. `GET /api/v1/chat/threads/{threadId}/stream?since={messageId}` streams the same rendered answer over SSE.
