# HealthRx

A specialty pharmacy care operations console — a demo application for Tanzu Platform for
Cloud Foundry. Spring Boot JSON API + React/Vite SPA + Postgres, built and deployed as a
single Cloud Foundry application.

> New here? Start with the [app overview](app-overview.md) — what HealthRx is, who uses it,
> and how the queue/workbench/dashboard work.
>
> Phase 1 scope, design contract, and metric definitions live in the docs at the repo root:
> [app-overview](app-overview.md) · [product-brief](product-brief.md) · [architecture](architecture.md) ·
> [data-model](data-model.md) · [api-contracts](api-contracts.md) ·
> [metric-definitions](metric-definitions.md) · [phase-1-implementation-plan](phase-1-implementation-plan.md) ·
> [phase-2-design](phase-2-design.md) · [phase-3-design](phase-3-design.md)

## Layout

```
healthrx/
  backend/    Spring Boot API (Spring Data JDBC, Flyway, Actuator) — also serves the built SPA + consumes events
  frontend/   React + Vite + TypeScript SPA (TanStack Query, React Router, Recharts)
  generator/  Phase 2 synthetic data generator (Spring Boot) — publishes the ambient event stream
  agents/     Phase 3 AI agents (Spring Boot + Spring AI), one subdirectory + CF app per agent
  mcp-servers/ Standalone MCP servers: knowledge/ (curated drug/disease guidance, Gradle) and
              postgres/ (read-only DB tools, Maven — vendored fork, see its README-upstream.md)
  cf-vars/    Foundation-specific deployment values (example.yml is the template)
  scripts/    Developer tooling (deterministic seed-data generator)
  manifest.yml, compose.yaml
```

## Prerequisites

- JDK 17+ (the build targets Java 17 bytecode; the Gradle wrapper is pinned to 8.11.1).
- Docker or Podman (for local Postgres and Testcontainers-based tests).
- Node is **not** required locally — the Gradle build downloads a pinned Node 20 toolchain.
  (For frontend-only iteration, any local Node 20+ works.)

## Build (single deployable artifact)

```bash
./gradlew clean build
```

This runs the Vite production build, packages the compiled assets into the Spring Boot boot
jar, and runs the test suite. The deployable artifact is:

```
backend/build/libs/healthrx.jar
```

`./gradlew printArtifact` prints that path.

## Run locally

1. Start Postgres:

   ```bash
   docker compose up -d      # or: podman compose up -d
   ```

2. Run the backend (Flyway applies the schema + deterministic seed data on startup):

   ```bash
   ./gradlew :backend:bootRun
   ```

   The API and (if built) the SPA are served from <http://localhost:8080>.

3. For fast frontend iteration, run the Vite dev server separately (it proxies `/api` and
   `/actuator` to the backend on :8080):

   ```bash
   cd frontend && npm install && npm run dev      # http://localhost:5173
   ```

### Application clock

Relative metrics and seed data are anchored to a **pinned demo clock**
(`2026-06-29T00:00:00Z` by default) so the dashboard is reproducible whenever the demo is
shown. In Phase 2 the canonical "now" is the shared simulated clock (`simulation_state`), seeded to
that anchor; it advances while the generator runs and freezes when paused.

### Phase 2 — live event simulation

The generator app publishes an ambient event stream to RabbitMQ that the API consumes, so the
queue/dashboard move on their own. See [phase-2-design](phase-2-design.md). Locally:

```bash
docker compose up -d                 # starts Postgres + RabbitMQ
./gradlew :backend:bootRun           # API + consumer (port 8080)
./gradlew :generator:bootRun         # generator + control API (port 8081)
```

Drive it from the **Simulation** bar in the UI, or via the API proxy:
`POST /api/simulation/start|stop`, `POST /api/simulation/speed?value=86400`,
`POST /api/simulation/scenario/{new-referral|advance-referral|send-at-risk|resolve-risk}`.

**Reset the demo** to a pristine state any time with the **Reset demo** button (or
`POST /api/admin/reset`): it pauses the simulation, wipes the data, re-applies the deterministic
seed, and resets the clock to 2026-06-29.

### Phase 3 — AI agents via the MCP gateway

The **Adherence Risk Agent** (`agents/adherence-risk-agent/`, its own CF app) senses `RefillMissed`
events, investigates the patient by writing SQL through the **MCP gateway** → Postgres MCP server
(every query is an audited tool call), reasons with its own marketplace **`ai-models`** LLM
instance, and posts a recommendation to the **Agents** view. A human approves; the agent then
executes the plan (outreach + intervention + refill) through the HealthRx-embedded **MCP action
tools** — idempotent per recommendation — and the patient's refill risk resolves HIGH → LOW.
The **Access Workflow Agent** (`agents/access-workflow-agent/`) is the autonomous half: it triages
new referrals and scans for stuck ones (baseline-suppressed so the seeded backlog never floods the
feed), then routes `[Agent]` follow-up tasks to the referral owner — no approval needed, everything
audited. A **knowledge MCP server** (`mcp-servers/knowledge/`) grounds both agents with curated
drug/disease guidance. See [phase-3-design](phase-3-design.md).

## Test

```bash
./gradlew test                 # backend (unit + controller + migration)
cd frontend && npm test        # frontend component tests
```

Migration/integration tests use Testcontainers. With Podman, ensure a machine is running
(`podman machine start`) and Testcontainers is pointed at the Podman socket
(`DOCKER_HOST`); these tests are skipped automatically when no container runtime is present.

## Deploy to Cloud Foundry

**Deploying to a brand-new foundation?** Follow [deploy-from-scratch](deploy-from-scratch.md) —
it lists every service to provision (Postgres, RabbitMQ, 2× ai-models, mcp-gateway), the
create-service commands, the bindings, and the push order.

Postgres is provisioned ahead of time and bound by name (it is **not** created by the deploy).

```bash
cf target -o shields-demo -s healthrx
./gradlew clean build
cf push --vars-file cf-vars/techbrese.yml          # deploys BOTH apps (healthrx + healthrx-generator)
# (cf push healthrx --vars-file …  to redeploy just the API)
```

`cf-vars/techbrese.yml` is filled in for the techbrese.com foundation (apps `healthrx` +
`healthrx-generator`, route `healthrx.apps.techbrese.com`, services `healthrx-postgres` +
`healthrx-rabbitmq`). Both services must be provisioned in the `healthrx` space before pushing.
Copy `cf-vars/example.yml` for other foundations.

If a foundation needs the service created first:

```bash
cf create-service <offering> <plan> healthrx-postgres
```

RabbitMQ (`healthrx-rabbitmq`) is reserved for Phase 2 and is not bound in Phase 1.
