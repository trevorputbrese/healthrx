# Phase 1 Data Model

This document is the build contract for the initial Postgres schema. Phase 1 should keep the model intentionally small, but the fields below are not optional unless implementation reveals a concrete reason to change them.

## Modeling Defaults

- Primary keys are `uuid`.
- Timestamps are `timestamptz`.
- Money values are `numeric(12,2)`.
- Percent values are API values, not stored percentages, unless called out below.
- Enumerations are stored as `text` with check constraints and mirrored as Java enums.
- Seed data uses fixed UUIDs so dashboard and UI demos are reproducible across rebuilds.
- The application, not the database, generates UUIDs for newly created records.

## Core Enums

### Referral Status

Canonical values:

```text
ELIGIBILITY_IDENTIFIED
BENEFITS_INVESTIGATION
PRIOR_AUTH_SUBMITTED
PRIOR_AUTH_APPROVED
PRIOR_AUTH_DENIED
FINANCIAL_ASSISTANCE_REVIEW
READY_TO_FILL
DELIVERY_SCHEDULED
ACTIVE_THERAPY
CANCELLED
```

Allowed transitions:

| From | To |
| --- | --- |
| `ELIGIBILITY_IDENTIFIED` | `BENEFITS_INVESTIGATION`, `CANCELLED` |
| `BENEFITS_INVESTIGATION` | `PRIOR_AUTH_SUBMITTED`, `FINANCIAL_ASSISTANCE_REVIEW`, `READY_TO_FILL`, `CANCELLED` |
| `PRIOR_AUTH_SUBMITTED` | `PRIOR_AUTH_APPROVED`, `PRIOR_AUTH_DENIED`, `CANCELLED` |
| `PRIOR_AUTH_APPROVED` | `FINANCIAL_ASSISTANCE_REVIEW`, `READY_TO_FILL`, `CANCELLED` |
| `PRIOR_AUTH_DENIED` | `PRIOR_AUTH_SUBMITTED`, `CANCELLED` |
| `FINANCIAL_ASSISTANCE_REVIEW` | `READY_TO_FILL`, `CANCELLED` |
| `READY_TO_FILL` | `DELIVERY_SCHEDULED`, `CANCELLED` |
| `DELIVERY_SCHEDULED` | `ACTIVE_THERAPY`, `CANCELLED` |
| `ACTIVE_THERAPY` | `CANCELLED` |
| `CANCELLED` | none |

Transition validation is enforced in the backend service. Every accepted transition writes `referrals.current_status`, the relevant milestone timestamp when applicable, one `referral_status_history` row, and `referrals.updated_at`. All timestamps are stamped from the application clock (see [Metric Definitions](metric-definitions.md#application-clock)).

Milestone timestamp writers (normative):

| Transition into | Timestamp written | Rule |
| --- | --- | --- |
| `BENEFITS_INVESTIGATION` | `benefits_investigation_started_at` | write-once (set only when null) |
| `PRIOR_AUTH_SUBMITTED` | `pa_submitted_at` | set to `now`; on resubmit from `PRIOR_AUTH_DENIED` also clear `pa_decided_at` |
| `PRIOR_AUTH_APPROVED` / `PRIOR_AUTH_DENIED` | `pa_decided_at` | set to `now` |
| `READY_TO_FILL` | `ready_to_fill_at` | write-once (reachable from three transitions) |
| `DELIVERY_SCHEDULED` | `delivery_scheduled_at` | write-once |
| `ACTIVE_THERAPY` | `active_therapy_at` | write-once; also applies the therapy activation side-effect below |
| `CANCELLED` | `closed_at` | set to `now` |

All milestone timestamps except `pa_submitted_at`/`pa_decided_at` are **write-once**: set only if currently null, so re-reaching `READY_TO_FILL` does not move the original milestone.

Therapy activation side-effect:

- On the transition into `ACTIVE_THERAPY`, the service sets the linked therapy's `status = ACTIVE` and, if `start_date` is null, sets `start_date` to the transition date. If `referrals.therapy_id` is null (e.g. a referral created live in Phase 2 with no therapy yet), the service **auto-creates** an ACTIVE therapy from the referral's patient/medication and links it (`referrals.therapy_id`).
- This keeps `active_therapy_at` (referral side) consistent with `therapies.status = ACTIVE` (therapy side), which the active-population, adherence, and refill-risk metrics depend on.
- Phase 1 has no therapy pause/resume endpoint. The Act 2 "patient returns to active status" beat is achieved by seeding the demo therapy `ACTIVE` throughout and letting refill risk rise from data and fall from a logged successful outreach / adherence intervention (see refill-risk resolution in [Metric Definitions](metric-definitions.md)).

### Other Enums

```text
priority: LOW, MEDIUM, HIGH, URGENT
task_status: OPEN, IN_PROGRESS, COMPLETED, CANCELLED
task_type: MISSING_LAB, PRIOR_AUTH_RENEWAL, PATIENT_CONTACT, FINANCIAL_ASSISTANCE, REFILL_FOLLOW_UP, CLINICAL_REVIEW
outreach_channel: PHONE, SMS, EMAIL, PORTAL
outreach_outcome: REACHED, LEFT_MESSAGE, NO_ANSWER, DECLINED, NEEDS_FOLLOW_UP
therapy_status: PENDING_ACCESS, ACTIVE, PAUSED, DISCONTINUED
fill_status: SCHEDULED, DISPENSED, DELAYED, CANCELLED, MISSED
intervention_type: ADHERENCE_COUNSELING, SIDE_EFFECT_MANAGEMENT, DOSE_CLARIFICATION, LAB_MONITORING, CARE_COORDINATION
```

## Tables

### `clinics`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `name` | `text` | Required |
| `region` | `text` | Required |
| `created_at` | `timestamptz` | Required |

Relationships:

- One clinic has many patients.
- One clinic has many referrals.

### `payers`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `name` | `text` | Required |
| `payer_type` | `text` | Required, for example commercial, Medicare, Medicaid |
| `created_at` | `timestamptz` | Required |

### `care_team_members`

This is the Phase 1 owner model. Authentication is deferred, so owners are seeded care team members.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `display_name` | `text` | Required |
| `role` | `text` | Required |
| `email` | `text` | Optional, unique when present |
| `active` | `boolean` | Required, default `true` |
| `created_at` | `timestamptz` | Required |

### `medications`

Medication names are fictional in Phase 1. Real disease-state labels are allowed.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `name` | `text` | Required |
| `disease_state` | `text` | Required |
| `route` | `text` | Required |
| `limited_distribution` | `boolean` | Required, default `false` |
| `active` | `boolean` | Required, default `true` |
| `created_at` | `timestamptz` | Required |

### `patients`

All patient data is fictional demo data.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `demo_mrn` | `text` | Required, unique, fictional |
| `first_name` | `text` | Required |
| `last_name` | `text` | Required |
| `date_of_birth` | `date` | Required |
| `disease_state` | `text` | Required |
| `clinic_id` | `uuid` | Required FK to `clinics.id` |
| `primary_owner_id` | `uuid` | Required FK to `care_team_members.id` |
| `payer_id` | `uuid` | Required FK to `payers.id` |
| `created_at` | `timestamptz` | Required |

Relationships:

- One patient can have many referrals.
- One patient can have many therapies over time.
- One patient can have many tasks, outreach events, interventions, and fills.

### `therapies`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `patient_id` | `uuid` | Required FK to `patients.id` |
| `medication_id` | `uuid` | Required FK to `medications.id` |
| `diagnosis` | `text` | Required |
| `disease_state` | `text` | Required |
| `status` | `text` | Required `therapy_status` |
| `start_date` | `date` | Nullable until active therapy starts |
| `end_date` | `date` | Nullable |
| `current_refill_due_date` | `date` | Nullable, updated from latest fill |
| `created_at` | `timestamptz` | Required |

Relationships:

- One therapy belongs to one patient and one medication.
- One therapy can have many fills.
- A referral may create or activate one therapy.

### `referrals`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `referral_number` | `text` | Required, unique, demo-friendly display value |
| `patient_id` | `uuid` | Required FK to `patients.id` |
| `clinic_id` | `uuid` | Required FK to `clinics.id` |
| `therapy_id` | `uuid` | Nullable FK to `therapies.id` |
| `medication_id` | `uuid` | Required FK to `medications.id` |
| `payer_id` | `uuid` | Required FK to `payers.id` |
| `owner_id` | `uuid` | Required FK to `care_team_members.id` |
| `current_status` | `text` | Required `referral_status` |
| `priority` | `text` | Required |
| `received_at` | `timestamptz` | Required |
| `benefits_investigation_started_at` | `timestamptz` | Nullable |
| `pa_required` | `boolean` | Required |
| `pa_submitted_at` | `timestamptz` | Nullable |
| `pa_decided_at` | `timestamptz` | Nullable |
| `financial_assistance_required` | `boolean` | Required |
| `financial_assistance_secured_amount` | `numeric(12,2)` | Required, default `0` |
| `copay_amount` | `numeric(12,2)` | Required, default `0` |
| `ready_to_fill_at` | `timestamptz` | Nullable |
| `delivery_scheduled_at` | `timestamptz` | Nullable |
| `active_therapy_at` | `timestamptz` | Nullable |
| `closed_at` | `timestamptz` | Nullable |
| `created_at` | `timestamptz` | Required |
| `updated_at` | `timestamptz` | Required |

Relationships:

- One referral belongs to one patient, clinic, medication, payer, and owner.
- One referral has many status history rows.
- One referral can have many notes, tasks, outreach events, and interventions.

### `referral_status_history`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `referral_id` | `uuid` | Required FK to `referrals.id` |
| `from_status` | `text` | Nullable for initial status |
| `to_status` | `text` | Required |
| `changed_at` | `timestamptz` | Required |
| `changed_by_id` | `uuid` | Nullable FK to `care_team_members.id` |
| `note` | `text` | Nullable |
| `phase2_event_type` | `text` | Nullable, must match event vocabulary when present |

### `referral_notes`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `referral_id` | `uuid` | Required FK to `referrals.id` |
| `author_id` | `uuid` | Required FK to `care_team_members.id` |
| `body` | `text` | Required |
| `created_at` | `timestamptz` | Required |

### `tasks`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `patient_id` | `uuid` | Required FK to `patients.id` |
| `referral_id` | `uuid` | Nullable FK to `referrals.id` |
| `owner_id` | `uuid` | Required FK to `care_team_members.id` |
| `type` | `text` | Required `task_type` |
| `status` | `text` | Required `task_status` |
| `priority` | `text` | Required |
| `title` | `text` | Required |
| `description` | `text` | Nullable |
| `due_at` | `timestamptz` | Nullable |
| `completed_at` | `timestamptz` | Nullable |
| `created_at` | `timestamptz` | Required |

### `outreach_events`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `patient_id` | `uuid` | Required FK to `patients.id` |
| `referral_id` | `uuid` | Nullable FK to `referrals.id` |
| `owner_id` | `uuid` | Required FK to `care_team_members.id` |
| `channel` | `text` | Required `outreach_channel` |
| `outcome` | `text` | Required `outreach_outcome` |
| `notes` | `text` | Nullable |
| `occurred_at` | `timestamptz` | Required |
| `created_at` | `timestamptz` | Required |

### `clinical_interventions`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `patient_id` | `uuid` | Required FK to `patients.id` |
| `referral_id` | `uuid` | Nullable FK to `referrals.id` |
| `owner_id` | `uuid` | Required FK to `care_team_members.id` |
| `intervention_type` | `text` | Required `intervention_type` |
| `summary` | `text` | Required |
| `occurred_at` | `timestamptz` | Required |
| `created_at` | `timestamptz` | Required |

### `fills`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key |
| `patient_id` | `uuid` | Required FK to `patients.id` |
| `therapy_id` | `uuid` | Required FK to `therapies.id` |
| `referral_id` | `uuid` | Nullable FK to `referrals.id` |
| `fill_number` | `integer` | Required |
| `status` | `text` | Required `fill_status` |
| `dispensed_at` | `date` | Nullable until dispensed |
| `days_supply` | `integer` | Required, positive |
| `expected_refill_date` | `date` | Required |
| `created_at` | `timestamptz` | Required |

Relationships:

- One fill belongs to one therapy and patient.
- Dispensed fills are the source for adherence and refill due calculations.

## Derived Values

The following values are computed by services and exposed through APIs. They are not directly stored unless listed above:

- `timeToTherapyDays`
- `priorAuthorizationAgeDays`
- `priorAuthorizationTurnaroundDays`
- `adherencePdcPercent`
- `refillRiskLevel`
- `refillRiskReasons`
- dashboard rollups and trends

See [Metric Definitions](metric-definitions.md) for formulas.

## Phase 2 Event Alignment

Phase 1 service methods should use method names and log event names that match the Phase 2 vocabulary. API actions map as follows:

| Phase 1 action | Phase 2 event name |
| --- | --- |
| Create seeded or future referral | `ReferralCreated` |
| Move to benefits investigation | `BenefitsInvestigationStarted` |
| Move to prior auth submitted | `PriorAuthorizationSubmitted` |
| Move to prior auth approved | `PriorAuthorizationApproved` |
| Move to prior auth denied | `PriorAuthorizationDenied` |
| Record assistance amount or move through assistance review | `FinancialAssistanceFound` |
| Move to ready to fill | `ReadyToFill` |
| Move to delivery scheduled | `DeliveryScheduled` |
| Move to active therapy | `TherapyActivated` |
| Cancel referral | `ReferralCancelled` |
| Record first dispensed fill | `PrescriptionFilled` |
| Create refill follow-up task | `RefillDue` |
| Mark expected refill missed | `RefillMissed` |
| Log outreach | `PatientOutreachLogged` |
| Log clinical intervention | `ClinicalInterventionCreated` |

These names are the single source of truth for the event vocabulary; the architecture and implementation-plan documents reference this table rather than re-listing the names. They are codified as one shared `WorkflowEventType` enum.

What Phase 1 writes:

- `referral_status_history.phase2_event_type` is populated on every status transition (and the initial seed row) using the status-transition rows above: `ReferralCreated`, `BenefitsInvestigationStarted`, `PriorAuthorizationSubmitted`, `PriorAuthorizationApproved`, `PriorAuthorizationDenied`, `FinancialAssistanceFound`, `ReadyToFill`, `DeliveryScheduled`, `TherapyActivated`, `ReferralCancelled`.
- `PatientOutreachLogged`, `ClinicalInterventionCreated`, and `FinancialAssistanceFound` (via the financials endpoint) are emitted as structured log lines from their service methods.
- `PrescriptionFilled`, `RefillDue`, and `RefillMissed` are **reserved** for Phase 2 (fills and tasks are read-only seed data in Phase 1); the enum reserves the names but Phase 1 does not emit them.

## Phase 2 Additions

The event-driven backbone (migration `V3`) adds:

| Table | Purpose |
| --- | --- |
| `processed_events` | Idempotency ledger. Each consumed `eventId` is claimed once (insert … on conflict do nothing) inside the apply transaction, so at-least-once delivery applies each event exactly once. |
| `simulation_state` | Single-row shared simulated clock: `enabled`, `current_instant`, `speed_seconds_per_second`. The generator advances `current_instant`; the API reads it as "now" (frozen when paused). |

Also seeded: two non-human `care_team_members` — **HealthRx System** (`00000000-…-0001`, event-applied writes) and **Care Agent** (`…-0002`, reserved for Phase 3). The `phase2_event_type` check constraint is extended with `AgentRecommendationCreated` / `AgentRecommendationApplied`.

Phase 2 event write paths: `ReferralCreated` creates a referral (+ initial history row); `PrescriptionFilled` inserts a DISPENSED fill and rolls `therapies.current_refill_due_date`; `RefillDue` opens a `REFILL_FOLLOW_UP` task; `RefillMissed` inserts a MISSED fill and rolls the due date into the past (drives overdue/HIGH risk). Status-movement and engagement events reuse the Phase 1 centralized services. `therapies.current_refill_due_date` is the canonical refill-due value the risk calc reads (kept in sync by fills/misses).


## Phase 3 Tables (V4/V5)

### `agent_recommendations`

One row per agent decision; written by the event consumer from `AgentRecommendationCreated`,
updated by approve/dismiss and the `Applied` repair handler. `status` CHECK: `PENDING`, `APPLYING`,
`APPLIED`, `AUTO_APPLIED`, `DISMISSED`, `SUPERSEDED`. Columns: `id` (PK, deterministic per
trigger), `agent_name`, `patient_id` (required FK), `referral_id`/`therapy_id`/`task_id` (nullable
FKs), `trigger_event_id`, `trigger_event_type`, `summary`, `recommendation` jsonb, `trace` jsonb,
`created_at`, `applying_at` (wall-clock; measures the approve proxy window), `decided_at`,
`decided_by_id` (FK, always a human — the owners lookup excludes non-human roles).

### `agent_tool_calls`

Apply-path idempotency ledger: PK `(recommendation_id, tool_name)`, `result` jsonb, `created_at`.
Written in the same transaction as the domain insert by the embedded MCP action tools; a replay
returns the stored result instead of re-executing. No FK to `agent_recommendations` (the Access
agent's `create_task` legitimately precedes the consumer-side recommendation insert).

### `agent_control`

Durable per-agent kill switch: `agent_name` PK, `paused` (V4 seeds both agents paused),
`updated_at`. The API upserts; agents read it per trigger via Postgres MCP and treat a missing row
as paused.

### Phase 3 actors, task type, and grants

V4 seeds fixed-UUID agent actors `…0003` (Adherence Risk Agent) and `…0004` (Access Workflow
Agent), role `AI Agent`; adds `ACCESS_FOLLOW_UP` to the `task_type` CHECK. Reset truncates the
agent tables, restores all non-human actors, and pauses both agents. V5 grants SELECT on all
public-schema tables (current + future) so the separately-bound Postgres MCP server role can read
them — that role is read-only at the database level.
