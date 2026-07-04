# Deploying HealthRx to a New Tanzu Platform for CF Foundation

Everything the demo needs, from an empty org/space to the verified demo beats. Total: **5
marketplace service instances** and **7 apps**, all built and pushed from this one repo.

| # | App | Binds | Route |
| --- | --- | --- | --- |
| 1 | `healthrx` (API + SPA + embedded MCP action server) | postgres, rabbitmq, **mcp-gw** (registers `/healthrx/mcp`) | public + `healthrx-mcp.apps.internal` |
| 2 | `healthrx-generator` | postgres, rabbitmq | public |
| 3 | `healthrx-adherence-agent` | rabbitmq, adherence LLM | public |
| 4 | `healthrx-access-agent` | rabbitmq, access LLM | public |
| 5 | `healthrx-knowledge-mcp` | **mcp-gw** (registers `/healthrx-knowledge-mcp/mcp`) | internal only |
| 6 | `healthrx-postgres-mcp-server` (`mcp-servers/postgres/`, Maven) | postgres, **mcp-gw** (registers `/healthrx-postgres-mcp-server/mcp`) | internal only |
| 7 | `clearpath-payer-portal` (ClearPath Benefits — stand-in **external partner**, `partners/payer-portal/`) | nothing (deliberately: it plays another company) | public |

Prerequisites: `cf` CLI v8+, JDK 17+ (backend/agents/knowledge server) — app 6 targets Java 21,
so JDK 21 too if you don't want to rely solely on the buildpack's JRE — and a foundation with the
**GenAI tile** (offerings `ai-models` + `mcp-gateway`), Postgres, and RabbitMQ in the marketplace,
plus the shared `apps.internal` domain (standard).

## 1. Target and provision the services (before any push)

Check exact offering/plan names first — they vary per foundation:

```bash
cf target -o <org> -s <space>
cf marketplace          # find the postgres, rabbitmq, ai-models, and mcp-gateway plans
cf buildpacks           # find the Java buildpack name (java_buildpack_offline vs java_buildpack)
```

Create all five instances (adjust plans to your marketplace):

```bash
cf create-service postgres    <postgres-plan>          healthrx-postgres
cf create-service p.rabbitmq  <rabbitmq-plan>          healthrx-rabbitmq
# One LLM instance PER agent so token usage attributes per agent on the platform dashboards.
# Pick a plan/model that supports TOOL CALLING (the agents are unusable without it).
cf create-service ai-models   <tool-calling-model-plan> healthrx-adherence-risk-agent-llm
cf create-service ai-models   <tool-calling-model-plan> healthrx-access-workflow-agent-llm
cf create-service mcp-gateway gateway                   healthrx-mcp-gw
```

Wait for `create succeeded` on all five (`cf services`), then note the gateway's client-facing
URL: `cf service healthrx-mcp-gw` → **dashboard url** (e.g. `https://healthrx-mcp-gw.apps.<domain>`).

## 2. Fill in the vars file

```bash
cp cf-vars/example.yml cf-vars/<foundation>.yml
```

Edit it: replace `<your-apps-domain>` in the routes, set `buildpack`, set `mcp_gateway_url` to the
dashboard URL from step 1, and generate the two agent secrets (`openssl rand -hex 16` each). Leave
the service-instance, app-name, and internal-route values as-is (they match the commands above).

**Also resolve `genai_model_name` now — don't skip this.** Some foundations' `ai-models`
credentials omit the model identifier entirely (see the gotcha below), and the agents will
silently pick the wrong model without it. Check before building:

```bash
cf create-service-key healthrx-adherence-risk-agent-llm tmp-key
cf service-key healthrx-adherence-risk-agent-llm tmp-key   # inspect the JSON
cf delete-service-key healthrx-adherence-risk-agent-llm tmp-key -f
```

If `credentials.model_name` is present, you can leave `genai_model_name` at its placeholder (it's
unused). If not — as with a `tanzu-gemma-*` plan, which only exposes `credentials.endpoint.*` —
call the endpoint's `config_url` (or `GET <api_base>/v1/models`) with the key from above to find
the advertised model id, and set `genai_model_name` to that exact string.

## 3. Build everything

```bash
./gradlew clean build                              # apps 1-5 + 7 (backend, SPA, generator, 2 agents, knowledge server, payer portal)
(cd mcp-servers/postgres && ./mvnw -q package -DskipTests)   # app 6 (Postgres MCP server, Maven)
```

`mcp-servers/postgres` is a vendored fork of [luanvuhlu/mcp-server-postgres](https://github.com/luanvuhlu/mcp-server-postgres)
(MIT license — see its `README-upstream.md`), kept on Maven since that's its native build; it
isn't part of the Gradle multi-project, so it needs this one extra command.

## 4. Push everything

```bash
cf push --vars-file cf-vars/<foundation>.yml
```

One push does all seven apps: the manifest binds each to its services, and the `healthrx-mcp-gw`
bindings **are** the MCP-server registrations (binding an app to the gateway registers it under
`/<app-name>/mcp` — that's why three apps carry internal routes: the gateway requires one on any
app it registers). Flyway migrates and seeds the database on the API's first start; both agents
come up **paused** (by design — resume them from the Agents view). The payer portal is reached by
the access agent over plain HTTPS via `HEALTHRX_PAYER_PORTAL_URL` (set in the manifest from
`payer_portal_route`) — an external partner API on purpose, not an MCP-gateway registration.

## 5. Verify

```bash
BASE=https://healthrx.apps.<domain>
GW=<gateway-url>

curl $BASE/actuator/health                     # {"status":"UP"}
curl $BASE/api/agents                          # both agents: paused=true, reachable=true

# Gateway registrations (each should return the server's tool list):
INIT='{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
for s in healthrx healthrx-knowledge-mcp healthrx-postgres-mcp-server; do
  curl -s -X POST "$GW/$s/mcp" -H "Content-Type: application/json" \
       -H "Accept: application/json, text/event-stream" -d "$INIT" | head -c 300; echo
done
```

Then run the demo beats from the UI at `$BASE`: **Reset demo** → resume the **Adherence Risk
Agent** (Agents view) → Simulation bar → `send-at-risk` → approve the recommendation → Marlowe
goes HIGH → LOW. Resume the **Access Workflow Agent** → `new-referral` → an `[Agent]` task
appears. (Full presenter script: phase-3-design.md §10.)

## Gotchas (each cost us time once)

- **"the bound application must have an internal route"** on a gateway bind → the app needs its
  internal route mapped *before or in the same push as* the gateway binding; the manifest already
  declares both together for the three registered apps, so a from-scratch push is unaffected —
  this only bites if you add internal-route mapping after the fact on an already-pushed app.
- **LLM plan must support tool calling**; the endpoint speaks OpenAI wire format but rejects
  `max_tokens` (Spring AI handles this) and only accepts default temperature.
- **`ai-models` credentials shape varies by foundation/plan.** One foundation's `tanzu-gemma-*`
  plan exposed only `credentials.endpoint.{openai_api_base,api_key,name,config_url}` — no
  top-level `api_base`/`api_key`/`wire_format`/`model_name` at all, unlike a `gpt-*` plan seen
  elsewhere that had both. The app code reads from `credentials.endpoint.*` (present both times)
  and gets the model id from the `genai_model_name` var / `GENAI_MODEL` env when
  `credentials.model_name` is absent — this is why step 2 has you check the credentials and the
  `config_url` *before* building. If an agent comes up with `reachable: true` but every
  recommendation silently never appears, this is the first thing to check.
- **Plan/offering/buildpack names differ per foundation** — never assume; check `cf marketplace`
  and `cf buildpacks`.
- **`mcp-servers/postgres` targets Java 21**, not 17 like the rest of the repo — its manifest
  entry pins `JBP_CONFIG_OPEN_JDK_JRE` to `21.+` independently.
- Gateway **audit logs** (`mcp.audit`) are emitted by the managed gateway — view them in Tanzu
  Hub / platform logging, not `cf logs` on the service instance.
- SSO on the gateway is optional/deferred: everything runs unauthenticated with per-agent
  shared-secret headers; when an SSO/OIDC service is available, rebind the three MCP servers with
  an `auth` config (phase-3-design.md §4).
