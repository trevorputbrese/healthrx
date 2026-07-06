-- Decouples "the clock is running" from "the ambient stream is emitting random events", so a
-- presenter can fast-forward simulated time with only their own scenario clicks landing —
-- no random noise racing the story on screen. Defaults to true (today's behavior unchanged).
alter table simulation_state add column ambient_enabled boolean not null default true;
