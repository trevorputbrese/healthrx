-- Phase 2: event-driven backbone. See phase-2-design.md.

-- Idempotency ledger for consumed events (at-least-once delivery -> apply each event once).
create table processed_events (
    event_id     uuid primary key,
    event_type   text        not null,
    source       text,
    status       text        not null,
    processed_at timestamptz not null
);

-- Shared simulated clock. The generator advances current_instant; the API reads it for "now"
-- when enabled, otherwise the API falls back to its pinned clock (Phase 1 behavior).
create table simulation_state (
    id                       integer primary key default 1 check (id = 1),
    enabled                  boolean     not null default false,
    current_instant          timestamptz not null,
    speed_seconds_per_second integer     not null default 86400, -- 1 simulated day per real second
    updated_at               timestamptz not null
);

insert into simulation_state (id, enabled, current_instant, speed_seconds_per_second, updated_at)
values (1, false, timestamptz '2026-06-29T00:00:00Z', 86400, timestamptz '2026-06-29T00:00:00Z');

-- Non-human actors so event-applied and (Phase 3) agent-applied writes have an attributable owner.
insert into care_team_members (id, display_name, role, email, active, created_at) values
  ('00000000-0000-0000-0000-000000000001', 'HealthRx System', 'System', null, true,
   timestamptz '2026-06-29T00:00:00Z'),
  ('00000000-0000-0000-0000-000000000002', 'Care Agent', 'AI Agent', null, true,
   timestamptz '2026-06-29T00:00:00Z');

-- Extend the event vocabulary with the Phase 3 agent events (reserved; emitted in Phase 3).
alter table referral_status_history drop constraint ck_status_history_event;
alter table referral_status_history add constraint ck_status_history_event
    check (phase2_event_type is null or phase2_event_type in
        ('ReferralCreated', 'BenefitsInvestigationStarted', 'PriorAuthorizationSubmitted',
         'PriorAuthorizationApproved', 'PriorAuthorizationDenied', 'FinancialAssistanceFound',
         'ReadyToFill', 'DeliveryScheduled', 'TherapyActivated', 'ReferralCancelled',
         'PrescriptionFilled', 'RefillDue', 'RefillMissed', 'PatientOutreachLogged',
         'ClinicalInterventionCreated',
         'AgentRecommendationCreated', 'AgentRecommendationApplied'));
