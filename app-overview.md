# HealthRx — App Overview

A plain-language guide to what HealthRx is, who uses it, and how it works. This is the
orientation doc for demo presenters and stakeholders. For the deeper build contract see
[product-brief](product-brief.md), [data-model](data-model.md),
[metric-definitions](metric-definitions.md), and [api-contracts](api-contracts.md).

## What HealthRx is

HealthRx is a specialty pharmacy **care operations console** — a simplified, demo-safe stand-in
for the kind of workflows Shields Health Solutions runs on its TelemetryRx platform. Shields
partners with health systems to operate their specialty pharmacy programs. The operational value
is to: capture the specialty prescription in-system, **remove access barriers** (eligibility,
benefits, prior authorization, financial assistance), get the patient **on therapy quickly**,
then **keep them adherent** and report outcomes back to the health system and payers.

HealthRx mirrors that with three views: an operational work queue, a patient care workbench, and
an outcomes dashboard.

## The core mental model: what moves through the app

The thing that flows through the queue is a **referral** — not a patient directly.

A **referral** is one specialty-pharmacy access case: *this patient, on this medication, under
this payer, from this clinic, owned by this care team member.* A patient can have more than one
referral over time (e.g. a second therapy later), but each referral is a single
"get this patient onto this therapy" journey.

The lifecycle:

> A prescriber sends a specialty prescription → it becomes a **referral** → the pharmacy team
> works it through access barriers → the patient reaches **Active therapy** → ongoing care
> continues in the **Patient Therapy Workbench**.

Prescribers (doctors/clinics) are the *source* of referrals; they are outside this app. The users
are the specialty pharmacy care team (see [Who uses it](#who-uses-it)).

## The three views

| View | Route | Purpose | Primary users |
| --- | --- | --- | --- |
| **Enrollment & Access Queue** | `/queue` | The operational worklist — every in-flight referral, filterable by status, clinic, disease state, payer, and owner, with time-pressure and financial columns front and center. "What needs attention, what's stuck, what's aging." | Pharmacy liaisons / patient access coordinators |
| **Patient Therapy Workbench** | `/patients/:id` (open a queue row → referral detail → click the patient) | The post-access, ongoing-care surface — the patient's therapies, adherence (PDC) score, refill risk, journey timeline, and where pharmacists log outreach and clinical interventions. "Keep them on therapy." | Clinical pharmacists, technicians |
| **Outcomes Dashboard** | `/dashboard` | The leadership/program view — active patients on therapy, median time-to-therapy, PA turnaround, refill-risk counts, adherence trend, financial assistance secured, and open work by owner. "Is the program performing?" | Program managers, pharmacy & health-system leadership |

A queue row opens the **referral access detail** (milestones, status advance, financials, notes,
history); from there the patient name links to the full **Patient Therapy Workbench**.

## Queue statuses (the access journey)

Each status is a real step in getting a specialty patient onto therapy. The allowed transitions
between them are enforced by the backend (see [data-model](data-model.md)).

| Status | What it means |
| --- | --- |
| **Eligibility identified** | Referral received; patient confirmed appropriate/eligible for the program (often EMR-driven identification). |
| **Benefits investigation** | Team verifies insurance coverage and what the payer will require (PA? what copay?). |
| **Prior auth submitted** | A prior authorization has been sent to the payer and is **pending a decision**. |
| **Prior auth approved** | The payer approved coverage. |
| **Prior auth denied** | The payer denied it (can loop back to resubmitted). |
| **Financial assistance review** | Securing copay assistance / foundation grants so the patient can afford the drug. |
| **Ready to fill** | All barriers cleared — the prescription can be dispensed. |
| **Delivery scheduled** | The fill is scheduled / shipped. |
| **Active therapy** | The goal state: the patient is on therapy. Ongoing care continues in the Workbench. |
| **Cancelled** | Referral closed without reaching therapy (patient declined, switched pharmacies, etc.). |

The point of the queue is to surface **where each referral is stuck and for how long** — because
time-to-therapy and prior-authorization delays are the barriers that lose patients and revenue.

## The "Days / PA Age" column

This is a single, context-aware "how long has this been waiting" number. It shows the most
relevant clock for each row:

- When a referral is in **Prior auth submitted** with no decision yet, it shows
  **PA Age** = days the prior authorization has been pending (e.g. `PA 4.4 d`). PA turnaround is
  the classic bottleneck in specialty access, so for those rows that's the number that matters —
  a high PA age means a payer is sitting on an authorization and may need a nudge.
- For every other status it shows **days since received** = how long since the referral came in
  overall (aging). A high value on an early-stage referral means intake is stalling.

Once a referral reaches **Active therapy**, the related headline metric becomes
**time-to-therapy** (received → active), which the dashboard reports as the program's access
speed. See [metric-definitions](metric-definitions.md) for the exact formulas.

## How it's used — the daily loop

1. A **patient access coordinator** opens the **Queue**, filters to their referrals or to a
   bottleneck status (e.g. "PA submitted, aging"), and works them: advancing status as benefits
   come back, PA is approved, and financial assistance is secured, up to **Ready to fill →
   Active therapy**. They record copay and assistance amounts and add notes along the way.
2. Once patients are active, **clinical pharmacists** work the **Workbench**: watching refill
   risk and adherence, logging **outreach** when a patient goes quiet, and logging
   **interventions** (adherence counseling, side-effect management) to resolve risk before a
   patient lapses.
3. **Leadership** watches the **Dashboard** to see whether the program is moving patients to
   therapy quickly, keeping them adherent, and securing financial assistance — the outcomes a
   health system and payers care about.

### Built-in demo narrative

The seeded data supports a two-act story (see [product-brief](product-brief.md) for the full
script):

- **Act 1 (Access):** a high-copay oncology referral needs financial assistance and a prior
  authorization; the coordinator works it through to ready-to-fill / active.
- **Act 2 (Adherence):** an MS patient (Marlowe Okafor, RX-10003) is Active on therapy but flagged
  **High refill risk** due to unanswered outreach; a pharmacist logs a successful outreach or an
  adherence intervention, the risk drops, and the dashboard updates.

This is also the setup for Phase 3: an AI agent can watch the same queue, summarize a case,
recommend next actions, and act as *another care-team actor*.

## Who uses it

The users are the **specialty pharmacy care team**, not prescribers. The seeded "Acting as" roles
reflect this (authentication is deferred in Phase 1, so a header selector chooses who is acting):

| Role | Where they work | What they do |
| --- | --- | --- |
| **Pharmacy liaison / patient access coordinator** | Queue + referral detail | Intake, benefits investigation, prior authorization, financial assistance — moving referrals to Active therapy. |
| **Clinical pharmacist** | Patient Therapy Workbench | Clinical review, adherence counseling, interventions, refill outreach. |
| **Pharmacy technician** | Queue / Workbench support | Outreach and supporting tasks. |
| **Program manager / pharmacy & health-system leadership** | Outcomes Dashboard | Monitor access speed, adherence, financial assistance, and team workload. |

Physicians/clinics are the **source** of referrals (and a future audience for outcome reports),
but the day-to-day operators of HealthRx are the pharmacy access and clinical teams.

## Why it matters to a company like Shields

- **Access barrier removal is the core service.** The queue makes benefits investigation, prior
  authorization, and financial assistance visible and trackable — the work Shields does to get
  patients on therapy.
- **Time-to-therapy and PA turnaround are headline KPIs.** The "Days / PA Age" column and the
  dashboard medians put those bottlenecks front and center.
- **Adherence and outcomes retain patients and revenue.** The Workbench's PDC adherence and
  refill-risk flags, plus interventions and outreach, mirror the clinical follow-up that keeps
  specialty patients on therapy.
- **Reporting closes the loop.** The dashboard gives leadership and (eventually) health-system
  partners the data-driven outcomes view that Shields' model is built on.

> All patient and medication data in HealthRx is fictional. It is a demo, not a production HIPAA
> system, and does not integrate with real EMR, payer, pharmacy, or manufacturer systems.
