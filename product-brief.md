# Product Brief

## Working Name

HealthRx

## Purpose

HealthRx is a specialty pharmacy demo application designed to make real-world specialty pharmacy workflows tangible on Tanzu Platform for Cloud Foundry.

The app should look and feel like a real specialty pharmacy operating system.  It only needs enough workflow depth for healthcare technology and platform leaders, platform engineering teams, Spring developers, and AI leadership to recognize the domain and see a credible path toward agentic capabilities.


## Demo Thesis

Tanzu Platform can host the core application, the data services, the event-driven data generator, and future AI agents in one operationally consistent environment. The same platform patterns enterprises already know for Spring applications can also support agentic applications with model access, observability, and governance.

## Phase 1 Scope

Phase 1 should focus on three recognizable specialty pharmacy workflows.

### 1. Enrollment And Access Queue

A work queue for new specialty pharmacy referrals.

Example capabilities:

- View incoming referrals by priority, disease state, medication, payer, and clinic.
- Track each referral through access milestones:
  - Eligibility identified
  - Benefits investigation
  - Prior authorization submitted
  - Prior authorization approved or denied
  - Financial assistance review
  - Ready to fill
  - Delivery scheduled
  - Active therapy
- Surface time-to-therapy, prior authorization age, copay amount, and owner.
- Allow simple status transitions and notes.

Why it matters:

This maps to how specialty pharmacy programs describe their work: patient enrollment, EMR-driven identification, benefits investigation, claims testing, and removing access barriers.

### 2. Patient Therapy Workbench

A patient detail view for managing therapy after referral intake.

Example capabilities:

- Patient profile with therapy, diagnosis, payer, clinic, and care team.
- Journey timeline showing referral, access, fill, refill, outreach, and intervention events.
- Refill due date and refill risk.
- Adherence indicator, such as a simplified PDC-style score.
- Outreach log and pharmacist intervention notes.
- Open tasks for missing labs, delayed refill, prior authorization renewal, or patient contact.

Why it matters:

This creates the operational surface that later agents can observe and assist. It also reflects specialty pharmacy workflows around adherence, refill tracking, clinical documentation, and coordinated care.

### 3. Outcomes Dashboard

An operational dashboard for leaders and care teams.

Example capabilities:

- Active patients on therapy.
- Median time to therapy.
- Prior authorization turnaround.
- Refill risk count.
- Adherence trend.
- Financial assistance secured.
- Open tasks by owner.
- Filters by clinic, disease state, payer, and medication.

Why it matters:

Specialty pharmacy programs emphasize data-driven workflows, real-time dashboards, adherence, outcomes, and reporting. A concise dashboard gives the demo an executive view without building a full analytics product.

## Demo Narrative

A new oncology referral appears in HealthRx. The queue shows high copay exposure and a prior authorization requirement. A pharmacy liaison starts benefits investigation, submits the prior authorization, records financial assistance, and moves the patient to ready-to-fill. The patient becomes active on therapy.

Later, synthetic data marks the refill as at risk. The Patient Therapy Workbench shows the missed outreach and intervention history. A pharmacist logs an intervention, the patient returns to active status, and the Outcomes Dashboard updates.

This narrative sets up Phase 3 cleanly: an agent can watch the same queue, summarize the case, recommend next actions, draft outreach, or escalate operational risk.

## Non-Goals

- Do not attempt to replicate a full production specialty pharmacy platform.
- Do not build a production-grade HIPAA system.
- Do not integrate with real EMR, pharmacy, payer, or manufacturer systems.
- Do not model every specialty pharmacy edge case.
- Do not add enterprise identity, audit, or reporting complexity unless needed for the demo.

## Design Tone

HealthRx should feel like a serious care operations tool:

- Dense enough for real work.
- Clear enough for a live demo.
- Healthcare-specific without using protected health information.
- Calm, clinical, and operational rather than playful or marketing-heavy.

