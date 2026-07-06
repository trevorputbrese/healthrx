Now I have the exact mechanics. Here's what's going on, both conceptually and in the UI.

## Why they don't toggle

These four aren't switches with an on/off state — they're **one-shot triggers**. Each click fires a burst of domain events into RabbitMQ once, the backend applies them (usually within a couple seconds), and the button is done. That's different from the **Start/Pause simulation** button next to them, which *is* a real toggle (it flips the shared clock between running and frozen) — that's the only stateful control in the bar. The four scenario buttons are more like "do this thing now" commands, so there's nothing to show as pressed/active afterward. The only feedback is indirect: the queue, dashboard, or a specific patient's risk badge changes a few seconds after you click, once the event round-trips through the consumer.

## What each one does

**New referral** — picks a random existing patient (plus a medication matching their disease state) and creates a **brand-new referral** for them, seeded as urgent, PA-required, needing financial assistance. It lands in the Enrollment & Access Queue at the very first stage, `ELIGIBILITY_IDENTIFIED`. Good for showing the "queue grows" story or feeding the Access Workflow Agent's triage.

**Advance a referral** — picks a random referral that's currently *in flight* and pushes it one step forward along its access-milestone path (`ELIGIBILITY_IDENTIFIED` → `BENEFITS_INVESTIGATION` → `PRIOR_AUTH_SUBMITTED`, or `FINANCIAL_ASSISTANCE_REVIEW` → `READY_TO_FILL` → `DELIVERY_SCHEDULED` → `ACTIVE_THERAPY`). It's random *which* referral gets picked, so you may need to click it a few times or watch the queue to see which row moved. **It never picks a referral sitting in `PRIOR_AUTH_SUBMITTED` or `PRIOR_AUTH_APPROVED`** — those two moments are real external decisions (the payer, a patient-assistance foundation), so only the Access Workflow Agent and the Financial Assistance Agent ever move a referral out of them; there is no coin flip anywhere in HealthRx standing in for an outside company's decision.

**Send at-risk** — this one's targeted, not random: it goes after **Marlowe Okafor** (`PX-2044`), the seeded MS patient, or falls back to a random therapy with a refill due soon if that patient isn't available. It fires three events: a missed refill (backdated 2 days, so the therapy goes overdue) plus two unsuccessful outreach attempts (a no-answer call, a left-message text). Per the refill-risk rules, that trips *both* HIGH-risk conditions at once — overdue refill and repeated failed outreach — which is why the patient's risk badge jumps straight to **HIGH**.

**Resolve risk** — the payback for the above, on the same targeted patient: a dispensed fill (clears the overdue condition), a `REACHED` phone outreach, and an `ADHERENCE_COUNSELING` intervention (together clear the failed-outreach condition). With both conditions cleared, risk drops back to **LOW**. This is also literally what the Adherence Risk Agent does automatically when you approve its recommendation — same three actions, agent-driven instead of scripted.

**Practical tip for demoing:** `send-at-risk` → `resolve-risk` back-to-back on Marlowe is the reliable, deterministic HIGH→LOW beat — that pairing is why they exist together. `new-referral` and `advance-referral` are looser, better for showing general queue movement than a specific before/after story.

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

## Other changes for the demo

- **Patients** is now in the top nav — a directory (search, disease-state filter, refill-risk rollup) that opens each Patient Workbench. Referral pages link to the patient record and back.
- **Live agent ticker** under the simulation bar on every page: the latest agent action, flashing when a new one lands. Click-through to the Agents view.
- **Agents view**: mission blurbs on each agent card, plain-English statuses ("Awaiting approval" / "Acted autonomously"), narrated traces with the raw MCP calls collapsed behind "raw call", and new feed entries flash.
- **Queue rows flash amber** when their status changes between polls — agent- and simulation-driven movement is visible without anyone clicking.

**Suggested 90-second agent arc:** Reset demo → resume all three agents → `submit-prior-auth` → watch the ticker + queue flash as the referral advances (often twice in a row: PA approval, then straight into a financial-assistance decision), expand both feed entries, show the ClearPath and BridgeFund portal tabs → `send-at-risk` → approve the Adherence agent's plan → `resolve-risk` story closes with the risk badge dropping.