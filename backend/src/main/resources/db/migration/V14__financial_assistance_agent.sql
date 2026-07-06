-- A third Phase 3 agent: the Financial Assistance Agent. Senses PriorAuthorizationApproved,
-- contacts the external BridgeFund Patient Assistance portal when a case needs copay
-- assistance, and records the decision. Mirrors V4's per-agent inserts for the first two.

insert into agent_control (agent_name, paused, updated_at) values
  ('financial-assistance', true, timestamptz '2026-06-29T00:00:00Z');

insert into care_team_members (id, display_name, role, email, active, created_at) values
  ('00000000-0000-0000-0000-000000000005', 'Financial Assistance Agent', 'AI Agent', null, true,
   timestamptz '2026-06-29T00:00:00Z');
