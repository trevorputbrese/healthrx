-- Wall-clock arrival stamp for the agent activity feed. created_at carries the SIMULATED
-- instant, which freezes while the sim is paused — ties then make "newest first" arbitrary and
-- the live ticker can miss a fresh agent run. inserted_at orders the feed by actual arrival.
alter table agent_recommendations
    add column inserted_at timestamptz not null default now();

create index idx_agent_recommendations_inserted_at on agent_recommendations (inserted_at desc);
