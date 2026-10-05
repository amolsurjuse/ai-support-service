# Sparky support-session investigation

For authenticated `SYSTEM_ADMIN` and `SUPPORT` users, the charging-session row offers **Diagnose with Sparky**. It opens the assistant,
selects that session and pre-fills the investigation request. Sending it produces
a read-only assessment and the complete evidence report, including when the model
is unavailable. The agent can ask follow-up questions using the selected session.

## Request flow

1. The portal sends the selected session ID and `SELECTED_RECORD` response mode.
2. The AI service requires exactly an authenticated `SYSTEM_ADMIN` or `SUPPORT` role and the tenant's
   `admin.sessions.diagnose` tool policy.
3. The AI service forwards the agent's bearer token through the gateway to
   `GET /session/api/v1/sessions/admin/{id}/diagnostics`. The gateway resolves
   administrative data scope; the session service calls `requireRead(id)` before
   collecting any evidence. Browser context never grants access.
4. The session service reads its own session, tariff, subscription, meter,
   lifecycle, and persisted billing/tax/invoice records. It reads payment
   authorization and correlated OCPP archive evidence through internal endpoints.
5. Sparky selects relevant fact indexes and a predefined read-only next check. The
   application validates that selection and renders the verified facts, then appends
   the evidence verbatim. Free-form model diagnoses and invented amounts are rejected.

When `AI_SUPPORT_MCP_ENABLED=true`, the AI host uses the private MCP service for
session evidence and organization, flow and cluster context. See
[MCP host integration](sparky-mcp-host.md) and [MCP service](../support-mcp-service/README.md).
The direct authorized gateway report remains the configuration used when MCP is
disabled; an enabled but unavailable MCP never triggers a less restricted fallback.

The route never reads the support agent's wallet or driver-only active-session API.
No stop, refund, authorization release, payment polling/reconciliation or billing
correction is performed by this workflow.

## Evidence and interpretation

- Session state, charger/connector and OCPP transaction IDs, lifecycle timestamps,
  payment-start confirmation and remote-stop request.
- Recorded authorized amount, currency, authorization status and release timestamps.
  This is a persisted payment snapshot, not a live bank hold/capture assertion.
- Session-specific subscription allocation, plan, quota usage, covered/uncovered
  energy and discount. Missing values remain unavailable, never zero by inference.
- Exact stored meter sample row count and first/last timestamps; these rows are
  distinct from OCPP messages, which may contain several samples.
- Session tariff rates, caps and idle parameters, plus persisted bill components.
  Active-session bills are provisional. Receipts and terminal states do not alone
  prove payment capture. No alternative AI bill calculation is used.
  Arithmetic checks compare gross minus the energy-capped subscription benefit
  plus tax with the stored total at currency precision. Tax line sums are checked
  against the stored tax. No-charge dispositions use the recorded waived amount.
  The legacy receipt builder is never invoked. Optional billing/tax/invoice columns
  are read through an allowlisted PostgreSQL projection; older schemas safely
  return null. Missing amounts or invoice snapshots remain explicit gaps.
- Counts and first/last timestamps for every persisted lifecycle event type,
  including OCPP meter/start/stop/status events. The chronological timeline shows
  up to 60 events (first 30 and last 30); omitted rows are reported explicitly.
- An incomplete start, terminal-completion wait or latest settlement failure is
  identified from recorded state. A later settlement success supersedes an earlier
  failure. PREPARING or missing telemetry alone does not prove a stuck session.

- The OCPP internal endpoint correlates request/response transaction IDs, including
  StartTransaction response IDs. Connector-only RemoteStartTransaction and
  StatusNotification records are marked `CONNECTOR_WINDOW_ONLY`, not confirmed
  session events. A different explicit transaction ID is excluded. OCPP 2.x EVSE
  IDs are never treated as OCPP 1.6 connector numbers.
- Archived exchange counts, response status, protocol errors and a bounded
  timeline; current connection/heartbeat is separately labeled as current data.
  Asynchronous recording captures processed inbound calls, queue rejection and
  completed/timed-out outbound commands. It stores allowlisted operational fields,
  excluding idTags, card data, arbitrary payloads and sampled-value arrays.

Archive scans cover at most 31 days and the newest 5,000 charger rows, with up to
60 matched timeline entries. Counts become explicit lower bounds when capped.
Retries are archive records, not deduplicated logical events. The end of the
window includes persisted server lifecycle timestamps, so a delayed terminal
callback is not excluded solely by the charger's stop timestamp. Async failures,
retention, incomplete commands, legacy records and uncorrelated frames can leave
gaps; absence never proves an exchange did not happen. This is curated diagnostic
evidence, not a complete raw OCPP export. The current session schema stores numeric
OCPP transaction IDs; OCPP 2.x string IDs require corresponding session mapping.
Authorization types absent from `payment_session_authorization`, including some
legacy/card-present paths, appear as unavailable rather than fabricated amounts.

## Deployment

Changes span `payment-service-refunds`, `session-service`, `ocpp-ha-hardening`,
`multi-tenant-ai-support`, `admin-portal-ui`, `api-gateway-readonly-release` and
`multi-tenant-user-service`. Deploy the backend endpoints and role policies before
enabling the portal action. The OCPP migration adds a charger/time/id archive index.
No deployment or production data changes were performed while implementing this.

Set session-service `app.services.payment.diagnostics-internal-token` (environment
`APP_SERVICES_PAYMENT_DIAGNOSTICS_INTERNAL_TOKEN`) from the secret matching payment
service `app.payment-gateway.routing.internal-token`. Do not put the secret in a
prompt, browser payload or repository. With no credential, only the payment snapshot
is marked unavailable. Restricted tenant policies must explicitly allow
`admin.sessions.diagnose`; wildcard policies already allow it.

The session and OCPP services must share `APP_SECURITY_INTERNAL_TOKEN` for
`GET /api/v1/ocpp/internal/session-evidence`; missing credentials disable only that
check. Existing `OCPP_SERVICE_URL` and `PAYMENT_SERVICE_URL` select the upstreams.
Tax/invoice completeness depends on persisted snapshots from the billing-enabled
session schema. This diagnostic change does not backfill historical bills.

Changing the selected session or page resets the portal conversation and discards
late responses from the previous selection.

`SUPPORT` operators enter a read-only Charging Sessions workspace. Gateway local
rules and the user-service `0042-support-session-investigation-rbac` migration
permit session reads, the hierarchy selectors, own scope/terms reads, own terms
acceptance and the AI chat POST. Migration `0043-support-ai-stream-rbac` adds the
specific AI SSE GET route. Existing signed scope resolution remains in
place: assign READ grants through the existing system-admin scope workflow;
SUPPORT by itself never grants global access. No users or scope grants are changed
by this implementation. The gateway rejects operational writes for support
operators even when their accounts also carry scoped roles. Existing SYSTEM_ADMIN
and ADMIN_READ_ONLY privileges retain their own behavior.

Before rollout, verify with a scoped SUPPORT account: allowed session, denied session,
missing authorization, pending start, idle/unplug, billing completion, settlement
failure followed by success and an AI provider outage. Verify gateway RBAC grants
the existing `/session/api/v1/sessions/admin/**` read path for the intended role.

## Jev routing

The optional `JevSupportRouter` calls Vercel's documented
[HTTP evaluation API](https://vercel.com/changelog/ai-gateway-now-supports-typesafe-clients-and-http-api-for-jev),
`POST https://ai-gateway.vercel.sh/v1/evaluate`, with model `typesafe-ai/jev`.
It handles ambiguous support questions with a selected session and no explicit
response mode. Choices are `session_investigation`, `knowledge`, and `unchanged`.
Existing explicit modes and deterministic investigation matches bypass Jev.
No customer/session identifiers, backend evidence, browser attributes or agent
credentials are supplied; questions use hosted-provider redaction and a 2,000
character limit. Requests require zero data retention and the TypeSafe provider.

Set `AI_JEV_ROUTING_ENABLED=true` and provide `AI_JEV_API_KEY` through secrets.
The authenticated tenant must enable AI and allow both `support.routing.jev` and
`admin.sessions.diagnose`. Default configuration makes no external routing calls.
The response must contain a known choice and valid probabilities summing to one;
the chosen probability must be at least 0.85. Timeout, invalid output, uncertainty
or an outage preserves deterministic routing. Connect/read timeouts are 500/1,000ms.

Jev only chooses a read-only investigation or a general explanation. It cannot
change the selected identity, grant access, calculate charges or execute actions.
Sparky's answer model explains evidence afterward. The adapter has mock HTTP
contract tests; provider credentials, representative routing evaluation and live
provider behavior have not been validated in this workspace.

## Local validation

The portal production build and all 72 portal tests passed. Focused AI
routing/answer/authorization/HTTP tests, session report/scope/billing/OCPP-client
tests, OCPP correlation/token/capture/command tests, and payment
snapshot/authentication tests passed. The actual billing SQL was exercised against
isolated PostgreSQL 16 with both legacy and extended schemas, confirming null
optional fields and exclusion of an unrelated customer-secret column.
The two new gateway access tests and all ten authorization-manager tests passed.
The support-role migration's forward application, repeated application and rollback
were exercised against isolated PostgreSQL, preserving unrelated policy rules.
The initial full-suite run encountered Windows Java HTTP loopback errors. Subsequent
implementation validation resolved these with `-Djdk.net.unixdomain.tmpdir=C:/electrahub/.tmp`;
the full AI and gateway suites now pass. Additional role, MCP transport, grounded
answer, cache and synthetic host/server checks are documented in
[MCP host integration](sparky-mcp-host.md). Live tenant access and deployed service
integration still require the rollout checks above.
