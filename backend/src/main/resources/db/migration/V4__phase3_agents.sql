-- Phase 3: AI agents. See phase-3-design.md.

-- Agent recommendations: one row per agent decision, written by the event consumer from
-- AgentRecommendationCreated and updated by the approve flow / AgentRecommendationApplied.
create table agent_recommendations (
    id                 uuid primary key,
    agent_name         text        not null,
    patient_id         uuid        not null references patients (id),
    referral_id        uuid        references referrals (id),
    therapy_id         uuid        references therapies (id),
    task_id            uuid        references tasks (id),
    trigger_event_id   uuid,
    trigger_event_type text,
    status             text        not null,
    summary            text        not null,
    recommendation     jsonb       not null,
    trace              jsonb       not null default '[]'::jsonb,
    created_at         timestamptz not null,
    applying_at        timestamptz,          -- wall-clock; measures the real-time proxy window
    decided_at         timestamptz,
    decided_by_id      uuid        references care_team_members (id),
    constraint ck_agent_rec_status check (status in
        ('PENDING', 'APPLYING', 'APPLIED', 'AUTO_APPLIED', 'DISMISSED', 'SUPERSEDED'))
);

create index ix_agent_rec_patient on agent_recommendations (patient_id, status);
create index ix_agent_rec_referral on agent_recommendations (referral_id, status);
create index ix_agent_rec_created on agent_recommendations (created_at desc);

-- Apply-path idempotency ledger: each action tool executes at most once per recommendation.
-- Written in the same transaction as the domain insert; replays return the stored result.
-- No FK to agent_recommendations: the Access agent's create_task legitimately precedes the
-- consumer-side recommendation insert.
create table agent_tool_calls (
    recommendation_id uuid        not null,
    tool_name         text        not null,
    result            jsonb       not null,
    created_at        timestamptz not null,
    primary key (recommendation_id, tool_name)
);

-- Durable per-agent kill switch. Agents ship paused; the API upserts, agents read via
-- Postgres MCP per trigger and treat a missing row as paused (fail-closed).
create table agent_control (
    agent_name text primary key,
    paused     boolean     not null default true,
    updated_at timestamptz not null
);

insert into agent_control (agent_name, paused, updated_at) values
  ('adherence-risk', true, timestamptz '2026-06-29T00:00:00Z'),
  ('access-workflow', true, timestamptz '2026-06-29T00:00:00Z');

-- Named agent actors (fixed UUIDs per the seed convention; restored by ResetService).
insert into care_team_members (id, display_name, role, email, active, created_at) values
  ('00000000-0000-0000-0000-000000000003', 'Adherence Risk Agent', 'AI Agent', null, true,
   timestamptz '2026-06-29T00:00:00Z'),
  ('00000000-0000-0000-0000-000000000004', 'Access Workflow Agent', 'AI Agent', null, true,
   timestamptz '2026-06-29T00:00:00Z');

-- Agent-created follow-up tasks get their own filterable type.
alter table tasks drop constraint ck_tasks_type;
alter table tasks add constraint ck_tasks_type check (type in
    ('MISSING_LAB', 'PRIOR_AUTH_RENEWAL', 'PATIENT_CONTACT', 'FINANCIAL_ASSISTANCE',
     'REFILL_FOLLOW_UP', 'CLINICAL_REVIEW', 'ACCESS_FOLLOW_UP'));
