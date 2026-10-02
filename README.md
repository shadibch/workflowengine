# Workflow Engine (WFE)

A BPMN 2.0 workflow engine: design process diagrams in the browser, publish
versioned definitions, and execute them on a custom token-based engine backed
by Postgres with Kafka messaging.

## Stack

| Layer      | Choice                                                       |
| ---------- | ------------------------------------------------------------ |
| Frontend   | React 19 + TypeScript + Vite, Ant Design (RTL-ready), TanStack Query, Zustand, `bpmn-js` modeler |
| Backend    | Java 21 (target), Spring Boot 3.4, multi-module Maven reactor |
| Storage    | PostgreSQL 16 (Flyway migrations)                            |
| Messaging  | Kafka (SPI; RabbitMQ/JMS in v1.5)                            |
| Identity   | Keycloak 26 OIDC tokens, roles/permissions DB-authoritative  |

## Repository layout

```
backend/    Spring Boot reactor: wfe-parent, wfe-bom, wfe-core,
            wfe-persistence, wfe-integration, wfe-security, wfe-app
frontend/   Vite + React SPA (designer, task inbox, dashboards)
infra/      local stack manifests (docker-compose, Keycloak realm)
docs/       architecture and delivery notes
```

## Local development

Start the dependencies, then the backend, then the SPA.

```sh
# 1. Dependencies (Postgres, Kafka KRaft, Keycloak)
docker compose up -d

# 2. Backend (port 8082; the 8080 on this host belongs to the HealthNexus
#    "ClinicFlow" stack, so the API and the Vite proxy use 8082)
cd backend
mvn -pl wfe-app spring-boot:run -Dspring-boot.run.profiles=local

# 3. Frontend (port 5173, proxies /api to 8082)
cd frontend
corepack pnpm install
cp .env.example .env.local   # adjust Keycloak URLs if needed
corepack pnpm dev
```

Open http://localhost:5173 and sign in with Keycloak (see credentials in
`docs/ARCHITECTURE.md`).

> The dev identity seed (`db/seed/R__dev_identity.sql`) runs only under the
> `local` profile. Preseeding Keycloak users with fixed IDs is impossible
> (Keycloak generates UUIDs), so the users are matched by username and the
> token subject is stamped onto the row on first login.

## Scripts

```sh
# Backend
mvn -pl wfe-app verify          # compile + tests for the runnable app
mvn -B compile                  # whole reactor

# Frontend
corepack pnpm lint
corepack pnpm build             # tsc -b && vite build
corepack pnpm dev
```

## Phase plan

0. Foundations (toolchain, compose, schema, security, designer spike) — in progress
1. Design & persistence (definitions CRUD + versioning API)
2. Designer (properties panel, palette polish, lint, import/export)
3. Engine core (token-based execution, gateway, timers, user tasks)
4. Services + Kafka (REST/SOAP/message tasks, retries)
5. Events + advanced (SSE dashboards, history, incident handling)
6. Simulation + dashboards (what-if, throughput, service-call analytics)
7. Hardening (audit, backup, SLAs)

See `docs/ARCHITECTURE.md` for the full delivery plan and the non-goals of v1
(no ad-hoc subprocess, compensation, or choreography; DMN/FEEL is v2).