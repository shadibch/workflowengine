# Architecture

## Product

A self-hosted workflow engine. Users design BPMN 2.0 processes in a browser
designer, publish versioned definitions, then start instances that the engine
executes. Instances traverse a token-based model: each flow node consumes a
token and emits tokens downstream; user-task work is delivered to an inbox;
service tasks call external systems (REST / SOAP / message brokers) with
response-driven continuation mapping.

Key non-goals for v1: ad-hoc subprocess, compensation, and choreography (no
`nrOfInstances` token splitting for multi-instance loops is implied - loops
use explicit gateways). DMN/FEEL decision nodes and XSD/OpenAPI form
generation land in v1.5/v2.

## Decisions (locked)

| Topic          | Decision                                                                  |
| -------------- | ------------------------------------------------------------------------- |
| Engine         | Custom, token-based, Postgres-backed (no Camunda/Flowable bootstrap)      |
| Designer       | `bpmn-js` + custom palette; standard BPMN elements carry `wfe:` attributes |
| Messaging      | `MessageChannel` SPI port; Kafka v1, RabbitMQ/JMS v1.5                    |
| Mapping        | JSONPath + XPath + JMESPath v1; XSD/OpenAPI forms v1.5; DMN/FEEL v2       |
| Identity       | Keycloak/OIDC tokens; roles/permissions evaluated from the database       |
| Tenancy        | Single tenant with a `tenant_id` column on every table                    |
| Task forms     | Custom JSON-Schema-driven form builder                                      |
| Scripts        | GraalJS sandbox (no classpath/outbound access)                            |

## Modules

```
                       +----------------------+
                       |    wfe-app (Boot)    |
                       |  REST / SSE / config |
                       +----------+-----------+
                                  |
        +-----------------+-------+-------+------------------+
        |                 |             |                    |
   wfe-security     wfe-persistence   wfe-integration     wfe-core
   JWT/OIDC/CORS      JPA+Flyway    X (Kafka/HTTP/etc)  PORTS + DOMAIN
        |                 |             |                    |
        +-----------------+-------+-------+------------------+
                                  |
                            PostgreSQL 16
```

`wfe-core` is a plain Java module: execution graph (`ProcessGraph`), node
matrix (`NodeType`), ports (SPI interfaces), and exceptions. No Spring
dependencies in it - everything downstream depends on it.

### SPI ports (`com.wfe.core.port`)

- `ExpressionEvaluator` - condition/loop expressions (language dispatch)
- `ResponseMapper` - JSONPath/XPath/JMESPath continuation mapping
- `ServiceInvoker` - REST/SOAP/Message call envelope
- `MessageChannel` - produce/consume (Kafka v1 ...)
- `IdentityProvider` - current caller, roles, permissions
- `JobQueue` - timer and retry scheduling
- `BusinessCalendar` - working hours for timers (v1.5)
- `ScriptEvaluator` - GraalJS sandbox
- `RuleEvaluator` - DMN/FEEL (v2)
- `InstanceEventPublisher` - outbox + SSE fan-out

## Designer extension strategy (verified)

The moddle descriptor (`frontend/src/designer/wfeDescriptor.ts`) uses
`extends` against standard BPMN types, the exact mechanism in the bpmn-io
`custom-meta-model` example:

- `Service` extends `bpmn:ServiceTask` with `wfe:kind`, `wfe:connection`,
  `wfe:operation`, `wfe:httpMethod`, `wfe:timeoutMs`, `wfe:retries`,
  `wfe:config` (JSON) attributes.
- These serialize as attributes on the same `<bpmn2:serviceTask>` tag, so the
  diagram stays valid BPMN 2.0 and unknown attributes are ignored by
  non-WFE tooling that still favors standard nodes.

The designer spike round-trips attributes both ways (import - edit - export -
re-import) without custom element types, which would have required custom
shapes.

## Detection / diagnostics

- Optimistic-lock with `version` columns everywhere; `etag` surfaced to the
  REST layer so the designer can warn on concurrent edits.
- Every table carries `created_at`/`updated_at`; audit of user actions lands
  in Phase 7.
- Request IDs flow `X-WFE-Request-Id` (browser) -> `wfe-request-id` header -> log
  correlation + Problem Detail responses (RFC 9457 `detail`/`requestId`).

## Dev identity (Keycloak realm `wfe`)

| Username   | Password       | Roles       |
| ---------- | -------------- | ----------- |
| admin      | admin123       | admin       |
| designer   | designer123    | designer    |
| ops        | ops123         | operations  |
| reviewer1  | reviewer123    | reviewer    |
| reviewer2  | reviewer123    | reviewer    |
| approver   | reviewer123    | approver    |
| viewer     | viewer123      | viewer      |

Clients: `wfe-frontend` (public, PKCE/S256, redirect `http://localhost:5173/*`)
and `wfe-api` (bearer-only).

## Open questions

- Phase 5 SSE: single dedicated connection vs per-page; browser ergonomics
  with bearer tokens (EventSource cannot set headers) - SSE will carry a
  short-lived one-time credential link or run through the same-origin proxy.
- GraalJS: image entropy - need to pin the exact feature set allowed in a
  script task (pure expressions in v1, transform functions later).