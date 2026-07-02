-- Phase 3: the Postgres MCP server binds healthrx-postgres with its own credentials (a separate
-- role per binding on this broker), so the agents' audited guard/context reads need SELECT on the
-- tables owned by the API's role. SELECT only — the MCP server stays read-only at the role level
-- (a strict improvement on the tool-level prefix guard noted in phase-3-design.md §5.1).
grant select on all tables in schema public to public;
alter default privileges in schema public grant select on tables to public;
