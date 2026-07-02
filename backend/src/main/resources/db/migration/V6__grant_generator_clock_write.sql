-- Phase 2: the generator app binds healthrx-postgres with its own separate role — this broker
-- gives every binding to the same instance a distinct role (the same behavior V5 found for the
-- Postgres MCP server's binding), so the generator's own role has no implicit write access to
-- simulation_state, only what V5's blanket SELECT grant provides. It needs UPDATE to advance the
-- shared simulated clock. Portable across foundations/bindings via PUBLIC (role names are
-- runtime-generated per binding, so a fixed role name can't be granted directly); scoped to just
-- this one table, unlike V5's broader read grant, since it's the only table the generator writes.
grant update on simulation_state to public;
