Now I have the exact mechanics. Here's what's going on, both conceptually and in the UI.

## Why they don't toggle

These four aren't switches with an on/off state — they're **one-shot triggers**. Each click fires a burst of domain events into RabbitMQ once, the backend applies them (usually within a couple seconds), and the button is done. That's different from the **Start/Pause simulation** button next to them, which *is* a real toggle (it flips the shared clock between running and frozen) — that's the only stateful control in the bar. The four scenario buttons are more like "do this thing now" commands, so there's nothing to show as pressed/active afterward. The only feedback is indirect: the queue, dashboard, or a specific patient's risk badge changes a few seconds after you click, once the event round-trips through the consumer.

## What each one does

**New referral** — picks a random existing patient (plus a medication matching their disease state) and creates a **brand-new referral** for them, seeded as urgent and PA-required. Whether it also needs financial assistance is a fact the referring provider reports at intake — simulated as a roughly 1-in-5 chance, so most new referrals *don't* need it and only some go on to trigger the BridgeFund beat below. It lands in the Enrollment & Access Queue at the very first stage, `ELIGIBILITY_IDENTIFIED`. Good for showing the "queue grows" story or feeding the Access Workflow Agent's triage.

**Advance a referral** — picks a random referral that's currently *in flight* and pushes it one step forward along its access-milestone path (`ELIGIBILITY_IDENTIFIED` → `BENEFITS_INVESTIGATION` → `PRIOR_AUTH_SUBMITTED`, or `FINANCIAL_ASSISTANCE_REVIEW` → `READY_TO_FILL` → `DELIVERY_SCHEDULED` → `ACTIVE_THERAPY`). It's random *which* referral gets picked, so you may need to click it a few times or watch the queue to see which row moved. **It never picks a referral sitting in `PRIOR_AUTH_SUBMITTED` or `PRIOR_AUTH_APPROVED`** — those two moments are real external decisions (the payer, a patient-assistance foundation), so only the Access Workflow Agent and the Financial Assistance Agent ever move a referral out of them; there is no coin flip anywhere in HealthRx standing in for an outside company's decision.

**Send at-risk** — targets **whichever active therapy was created most recently**. Since July 2026, **Reset demo seeds two referrals already at `Active therapy`** (RX-10001 and RX-10002, walked histories, one dispensed fill each), so this button works immediately after a reset — RX-10002's therapy is the newer one, so it's targeted first, leaving RX-10001 for a second run. It fires three events: a missed refill (backdated 2 days, so the therapy goes overdue) plus two unsuccessful outreach attempts (a no-answer call, a left-message text). Per the refill-risk rules, that trips *both* HIGH-risk conditions at once — overdue refill and repeated failed outreach — which is why the patient's risk badge jumps straight to **HIGH**. If there's no active therapy at all (both seeded ones cancelled and none walked up), it does nothing (`emitted: 0`).

**Resolve risk** — the payback for the above, on the same targeted patient: a dispensed fill (clears the overdue condition), a `REACHED` phone outreach, and an `ADHERENCE_COUNSELING` intervention (together clear the failed-outreach condition). With both conditions cleared, risk drops back to **LOW**. This is also literally what the Adherence Risk Agent does automatically when you approve its recommendation — same three actions, agent-driven instead of scripted.

**Practical tip for demoing:** `send-at-risk` → `resolve-risk` back-to-back is the reliable, deterministic HIGH→LOW beat — that pairing is why they exist together. `new-referral` and `advance-referral` are looser, better for showing general queue movement than a specific before/after story.

---

# The payer beat (added July 2026)

**Submit prior auth** is the fifth scenario button, and it's the headline agent moment: it pushes the oldest pre-PA referral into `PRIOR_AUTH_SUBMITTED` (chaining the benefits step first if needed). Within a few seconds the **Access Workflow Agent** (must be resumed/Active) picks the event up and, entirely on its own:

1. Looks up the referral, patient, medication, and payer — audited SQL through the MCP gateway.
2. **Calls ClearPath Benefits** — a separate CF app (`clearpath-payer-portal`) playing the payer's prior-auth portal, over its plain public REST API. It "reviews" for ~1 second.
3. On **APPROVED**: records the decision through the `record_prior_auth_decision` MCP tool — the referral **advances to Prior auth approved in the queue by itself**, with the auth number and payer in the status history. The portal's **first two submissions after a fresh start or demo reset always approve**, so your opening click is guaranteed to land the clean "agent advanced it" beat.
4. On **DENIED** (~20% of first submissions after the warm-up, deterministic per referral number): records the denial *and* routes a HIGH-priority appeal task to the owner. If anyone resubmits that PA (Advance status on the referral page → Prior auth submitted), the portal approves the appeal — a nice two-act story. **Reset demo** also clears the portal's memory, so reruns behave identically.

Every run lands in the Agents activity feed as **Acted autonomously**, with the step-by-step trace ("Contacted ClearPath Benefits — the payer's portal, outside HealthRx"). The same beat also fires when **you** advance a referral into Prior auth submitted from the referral detail page — the API re-broadcasts human-driven PA submissions so the agent notices those too. And while the ambient simulation runs, its own PA submissions keep the agent visibly working in the background.

**Show the other side:** open `https://clearpath-payer-portal.apps.<domain>/` in a second tab — a deliberately foreign-looking purple portal page listing every request it received and the decision it issued, refreshing every 5 s. Great for proving there's a real external system on the other end.

---

# The benefits beat and the task↔referral link (added July 2026)

One human touch is now all a referral needs before the agents carry it to `Ready to fill`.

**The benefits beat.** The moment a referral enters `Benefits investigation` — you advancing it from the referral page, you completing the intake task (below), the `advance-referral` scenario, or the ambient stream — the **Access Workflow Agent** picks it up, runs the coverage check against the case record (payer, plan type, medication, expected copay, financial-assistance flag — audited SQL through the MCP gateway), and **submits the prior authorization itself** through the new `submit_prior_auth` MCP tool. That lands the referral in `Prior auth submitted`, which hands it straight to the payer beat above. The full chain from that one touch: benefits check → PA submitted → ClearPath decision → (BridgeFund, if assistance is needed) → `Ready to fill`, each step its own feed entry, all within ~10 seconds.

**Tasks now do something.** The Tasks queue and the referral lifecycle are linked in both directions:

- **Completing an agent-routed access task does the work it asked for.** The intake-review task the agent files for every new referral completes-and-advances: the referral moves to `Benefits investigation` and the chain above fires. Same pattern for a PA-denial appeal task (completing it resubmits the PA — ClearPath fast-tracks the appeal) and a financial-assistance review task (releases the fill). The button reads **Complete & advance** whenever this applies, the expanded row spells out exactly what will happen, and a green confirmation banner links to the referral afterward.
- **Advancing a referral auto-completes its open tasks.** Move a referral forward from the queue or referral page and any open tasks on it complete themselves — the world moved past the ask, so the queue reflects that. Cancelling a referral cancels its open tasks. Refill follow-up tasks are exempt (they track the therapy refill cycle, not the access lifecycle).

**The minimal on-stage loop:** `new-referral` → open **My Tasks** → **Complete & advance** on the agent's intake task → watch the ticker fire two or three times in a row as the referral sails to `Ready to fill`.

---

# The financial-assistance beat (added July 2026)

There's no dedicated scenario button for this one — it fires **automatically** the instant a referral reaches `PRIOR_AUTH_APPROVED`, whether that happened via `submit-prior-auth` → ClearPath approval, a human advancing the referral, or the ambient stream. That means the `submit-prior-auth` beat above often chains straight into this one with no extra clicks.

A brand-new agent, the **Financial Assistance Agent** (resume it separately from the Agents view — three agents now), reacts:

1. Looks up the referral and reads its `financial_assistance_required` flag — a fact recorded at intake, not a guess.
2. **If assistance isn't required**: advances the referral straight to `Ready to fill` itself. No external call — there's nothing to decide, so nothing gets decided.
3. **If it is required**: asks its model for a one-sentence plain-English justification (this is the *only* place the LLM is in the loop here — it narrates the case, it never decides approve/deny/amount), then **calls BridgeFund Patient Assistance** — a second external CF app (`bridgefund-assistance-portal`) playing an independent copay/patient-assistance foundation, over its own plain public REST API.
4. BridgeFund answers **APPROVED** (with a dollar amount) or **DENIED** (with a reason) — its **first two requests after a fresh start or reset always approve**, same guaranteed-clean-opening-beat trick as ClearPath. Either way the referral **still advances to Ready to fill** (assistance is a bonus, never a blocker) — only the secured-amount field differs.

Every run lands in the Agents feed as **Acted autonomously**, with its own trace ("Reasoned" for the model's justification, "External call" for BridgeFund) and a green/teal "Financial assistance" outcome chip.

**Show the other side:** open `https://bridgefund-assistance-portal.apps.<domain>/` in a second tab — same idea as the ClearPath tab, teal-themed so the two are easy to tell apart at a glance.

**Why this exists:** every decision in the referral lifecycle that a real outside party would make — the payer's coverage decision, the foundation's grant decision — now has a visible, named external app behind it, and there is no random-chance logic anywhere in HealthRx standing in for one of those calls. If an agent is paused, the referral it would have handled simply waits, same as a real case with nobody following up — never a silent coin flip.

---

# The Assistant (added July 2026)

**AI → Assistant** in the nav: a chat panel where *you* are the MCP client. Ask about any
formulary medication or disease state ("What's the missed-dose guidance for Neurosphere?",
"Outreach tips for rheumatology patients?") and the assistant answers by calling the knowledge
MCP server's tools **through the same MCP gateway the agents use** — its own marketplace LLM
instance (`healthrx-chat-assistant-llm`), its own audited gateway calls. Each reply shows a
**tool chip** ("gateway tool · get_medication_guidance"); hover it for the exact arguments the
model sent. That's the story beat: the agents' governed tool plane isn't agent-only
infrastructure — a human-facing feature rides the same rails, and both show up in the same
gateway audit logs.

The formulary is fictional demo content (the 12 seeded medications across the 4 disease
states); asked about anything else, the assistant says so instead of inventing guidance.
Postgres MCP tools ("which of my patients are on Neurosphere?") are a planned follow-on, not
mounted yet. Architecture sketch: [docs/chat-assistant-architecture.svg](docs/chat-assistant-architecture.svg)
(walkthrough in [architecture.md](architecture.md)).

---

# Starting from a near-empty queue (updated July 2026)

**Reset demo** leaves the referral queue empty **except for two seeded referrals already at `Active therapy`** (RX-10001, RX-10002 — the two lowest-MRN patients, full walked status histories, linked therapies, one dispensed 30-day fill each). They exist so the adherence-risk flow (`send-at-risk`) has a target immediately after a reset, with no setup walk. Their therapies start 7 and 6 sim-days before the reset clock, so their risk reads LOW ("new therapy" on the PDC side) until you fire the scenario — and there's about three sim-weeks of clock runway before natural refill-due pressure would surface on its own.

Everything else starts blank: patients, clinics, medications, payers, and care team members are fully seeded (80 patients, ready for `new-referral` to pick from), and the access-workflow story is still built live on stage from `new-referral`.

**To walk another referral to `Active therapy` yourself** (optional — for a third at-risk run, or to show the full lifecycle):

1. `new-referral` — creates one at `Eligibility identified`; the Access Workflow Agent triages it and routes an intake task to **My Tasks** within a few seconds.
2. **Complete & advance** that task (or advance the referral to `Benefits investigation` from the queue/referral page, or click `submit-prior-auth` as the zero-touch shortcut) — the Access Workflow Agent runs the benefits check, submits the PA, contacts ClearPath, and records the decision; if approved, the Financial Assistance Agent follows within seconds and lands it at `Ready to fill` either way. One touch total (both agents must be resumed).
3. `advance-referral` twice (or the manual status dropdown on the referral detail page) — `Ready to fill` → `Delivery scheduled` → `Active therapy`. With only a few referrals in flight, `advance-referral`'s random pick reliably lands on the right one; the manual dropdown is fully deterministic if you want to be sure.
4. That referral's therapy is now the newest, so `send-at-risk` targets it next.

The two seeded active-therapy referrals are invisible to `advance-referral` (there is nothing left to advance them to), so they sit untouched until `send-at-risk` uses them.

## Other changes for the demo

- **Patients** is now in the top nav — a directory (search, disease-state filter, refill-risk rollup) that opens each Patient Workbench. Referral pages link to the patient record and back.
- **Live agent ticker** under the simulation bar on every page: the latest agent action, flashing when a new one lands. Click-through to the Agents view.
- **Agents view**: mission blurbs on each agent card, plain-English statuses ("Awaiting approval" / "Acted autonomously"), narrated traces with the raw MCP calls collapsed behind "raw call", and new feed entries flash.
- **Queue rows flash amber** when their status changes between polls — agent- and simulation-driven movement is visible without anyone clicking.

**Suggested demo arc:** Reset demo → resume all three agents → `new-referral` (the agent's intake task lands in My Tasks) → **Complete & advance** the intake task → watch the ticker + queue flash as the referral chains by itself (benefits check → PA submitted → ClearPath approval → a financial-assistance decision if this one needed it), expand the feed entries, show the ClearPath and BridgeFund portal tabs → then the adherence story needs no setup: `send-at-risk` (hits seeded RX-10002) → approve the Adherence agent's plan and watch HIGH drop to LOW — or `resolve-risk` for the scripted no-agent version.