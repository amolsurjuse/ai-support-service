# AI Support Service

ElectraHub AI support assistant for driver-facing iOS chat and internal CSR diagnostics.

## Current scope

- `POST /api/v1/chat/messages` creates or continues a chat thread.
- `GET /api/v1/chat/threads/{threadId}/stream?since={messageId}` streams an iOS-compatible SSE response.
- Driver mode is safe by default and does not expose engineer-only internals.
- The first implementation uses deterministic diagnostics so iOS can integrate before the LLM and live support tools are enabled.

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

The service sends only the redacted user message, screen/context identifiers, deterministic fallback answer, and summarized live backend facts to the LLM. It never sends bearer tokens or raw secrets to the provider. If OpenAI is disabled, unreachable, or returns no usable text, Sparky returns the deterministic diagnostic answer.

`POST /api/v1/chat/messages` returns a normalized final answer for all clients. `GET /api/v1/chat/threads/{threadId}/stream?since={messageId}` streams the same rendered answer over SSE.
