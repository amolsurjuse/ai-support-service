# Private support context MCP service

This independently runnable Java 21 / Spring Boot 4 service gives Sparky four read-only tools. Every MCP request requires the existing gateway HMAC identity envelope, the original bearer credential and exactly `SYSTEM_ADMIN` or `SUPPORT`. Hosting internally does not bypass role or domain authorization. No generic HTTP, SQL, shell, Kubernetes mutation or credential-reading tool exists.

## Run and connect

Build from this directory with `mvn verify` (or from the AI repository with `mvn -f support-mcp-service/pom.xml verify`). Set `APP_INTERNAL_ACCESS_CONTEXT_SECRET` to the gateway/AI host's provisioned non-default secret of at least 32 characters, then run `java -jar target/support-mcp-service-1.0.0-SNAPSHOT.jar`. The default bind is localhost:8095. The standalone container binds 0.0.0.0:8095 and must remain private. Point the AI host to `http://support-mcp-service:8095/mcp` and enable its support MCP feature flag after deployment. Configuration never enables a public ingress.

`deploy/kubernetes-example.yaml` supplies a private Service, single replica with persistent volume, namespace-scoped read-only account, resource limits and restricted network policy. Before using it, configure the image digest, namespace, actual workload labels, cluster identity, storage class if needed and API-server destination CIDR. Provision the identity secret separately. The cluster collector is disabled in both application and example deployment until explicitly enabled. No manifest has been applied to a live cluster.

## Protocol and contracts

POST `/mcp` implements stateless [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), JSON response mode, pinned to `2025-11-25`. Methods: `initialize`, `ping`, `tools/list`, `tools/call`; initialized/cancelled notifications receive 202. GET and DELETE return 405. Send `Content-Type: application/json`, `Accept: application/json, text/event-stream`, `MCP-Protocol-Version: 2025-11-25`, bearer token and all four signed gateway headers. A fresh signature expiring within 120 seconds is required on every call. Browser Origin headers are rejected. The HMAC envelope is the private ElectraHub transport authorization profile, not public MCP OAuth interoperability.

| Tool | Arguments | Data and authority |
| --- | --- | --- |
| `get_service_topology` | `{}` | Sanitized fresh Kubernetes inventory; no tenant ownership or actual call graph inference |
| `get_flow_definition` | `{"flowId":"charging-session"}` | Packaged reviewed source-based flow, source paths and commit provenance |
| `get_org_context` | `{"sessionId":"<UUID>"}` | Fresh authorized session aggregate, projected to stored enterprise/network/location/charger mapping |
| `get_session_evidence` | `{"sessionId":"<UUID>"}` | Fresh authorized report with `sessionId`, `collectedAt`, `facts`, `gaps` |

Tool success is `{content:[{type:"text",text:"<JSON>"}],structuredContent:<object>,isError:false}`. Tool evidence failures return `isError:true`, without backend payloads or access distinctions. Invalid tools/arguments return JSON-RPC -32602. Clients must not treat errors or missing data as a diagnosis. Arguments are exact, allowlisted schemas; no URL, namespace, organization, actor or tenant selector is accepted from the model.

Both customer-context tools forward the original bearer through the fixed gateway route `GET /session/api/v1/sessions/admin/{sessionId}/diagnostics`. The gateway and domain service re-evaluate current identity and authorized session scope. There is no customer-data cache. `requesterTenantId` is distinguished from the session tenant: the session schema currently has no persisted tenant ID, so missing session ownership is reported as a gap. Organization information never comes from namespace names or Kubernetes labels. Audits use hashed actor/tenant IDs and fixed tool/outcome values, with no payloads or tokens.

## Local knowledge and memory

* `knowledge/charging-session.json` contains curated expected stages, evidence interpretation, scoped API mapping and repository provenance. It loads once and is content-hashed for deterministic retrieval. The source baseline is distinguished from working-tree additions and is not asserted to match live deployed code.
* `knowledge/repository-catalog.json` records all 29 logical repositories from the reviewed design inventory. It is discovery metadata, not permission to expose all APIs. Update this file and the flow resource through reviewed repository changes and repackage the service; automatic source-code/vector ingestion is not implemented.
* `topology-v1.json` in `SUPPORT_MCP_MEMORY_DIRECTORY` is an atomically replaced, bounded, versioned infrastructure snapshot. It survives process restarts. Scope hash includes cluster identity, namespaces and TTL, so changing scope invalidates old snapshots. Invalid/corrupt/incompatible data is ignored. Customer reports, organization facts, prompts, bearer tokens and secrets are never written here.
* Default freshness is 180 seconds. Expired inventory is withheld from tool resources and returned with explicit gaps. Disabled collection never exposes a disk snapshot. A failed refresh cannot extend old freshness. The current storage example supports one replica per volume. Do not share this file store between writers; use independent per-replica volumes/reconciliation or a separately designed shared database for a future HA deployment.

## Cluster collector

Enable `SUPPORT_MCP_CLUSTER_ENABLED=true` with `SUPPORT_MCP_CLUSTER_ID` and an explicit comma-separated allowlist of one to four namespaces. A 60-second bounded scheduled reconciliation lists only Services, Deployments, StatefulSets and EndpointSlices. This version uses reconciliation, not watches. It reads the projected service-account token on each cycle and verifies TLS using the mounted Kubernetes CA; redirects are disabled. It stores only kind/name/namespace/resourceVersion, approved version label, container image references, ready/desired counts and EndpointSlice service name. No annotations, environment variables, Secret/ConfigMap content, pod logs, exec, endpoint IPs or raw manifests reach memory or the model.

Each HTTP response is limited to 2 MiB and a 3-second whole-body deadline; a collection supports at most 256 total resources and refuses server-side continuation rather than silently publish a partial graph. A failed/oversized cycle preserves the last complete snapshot with its original expiry. At four namespaces the upper request-time bound is about 48 seconds, followed by the configured fixed delay. For larger clusters, implement bounded pagination with a consistent snapshot or an approved watch collector before increasing scope. Inventory readiness indicates resource state; actual dependencies/flows require catalog or correlated runtime evidence.

## Operational boundaries

Request bodies are limited to 16 KiB; gateway responses to 192 KiB; each tool's serialized content to 96 kB. Gateway calls have a 6-second whole-body deadline and never follow redirects. The AI host must retain its overall investigation budget and concurrency/quota limits. Actuator health and Prometheus run separately on localhost:8097 (deployment enables cluster reachability under network policy). Metrics include `support.mcp.tool.calls` and `support.mcp.cluster.reconcile` with bounded tags. Monitor failed reconciliation and absent/failing tool calls; source knowledge remains available during cluster outages.

Tests cover signed/expired/tampered identities, role exclusions, MCP envelopes and schemas, domain credential forwarding, unavailable/mismatched responses, organization projection, cache scope/expiry/corruption, inventory secret removal, and bounded transport reception. Production rollout still requires private network verification, gateway/domain image compatibility, provisioning, staging integration and operator enablement. This is retrieval/context grounding; no model fine-tuning job or automatic learning from customer conversations is run.

On Windows hosts where Java reports `Unable to establish loopback connection` from UnixDomainSockets, use a short existing temporary directory for the forked tests, for example `mvn "-DargLine=-Djdk.net.unixdomain.tmpdir=C:/electrahub/.tmp -Djava.io.tmpdir=C:/electrahub/.tmp" verify`. This is a host socket-path workaround, not a network or authorization bypass.
