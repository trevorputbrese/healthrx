package com.healthrx.repo;

import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.healthrx.web.dto.CommonDtos.EntityRef;
import com.healthrx.web.dto.CommonDtos.NamedRef;

/** Lookup values backing filters and form dropdowns. */
@Repository
public class LookupRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LookupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EntityRef> clinics() {
        return jdbc.query("select id, name from clinics order by name",
                (rs, i) -> new EntityRef(Columns.uuid(rs, "id"), rs.getString("name")));
    }

    public List<EntityRef> payers() {
        return jdbc.query("select id, name from payers order by name",
                (rs, i) -> new EntityRef(Columns.uuid(rs, "id"), rs.getString("name")));
    }

    public List<EntityRef> medications() {
        return jdbc.query("select id, name from medications where active = true order by name",
                (rs, i) -> new EntityRef(Columns.uuid(rs, "id"), rs.getString("name")));
    }

    public List<NamedRef> owners() {
        // Non-human actors (System, AI agents) resolve by id for display but never appear in the
        // Acting-as selector, owner filters, or assignment dropdowns. See phase-3-design.md §7.
        return jdbc.query("""
                select id, display_name from care_team_members
                where active = true and role not in ('System', 'AI Agent')
                order by display_name""",
                (rs, i) -> new NamedRef(Columns.uuid(rs, "id"), rs.getString("display_name")));
    }

    /** Distinct disease states present in the data, so filters can never drift from rows. */
    public List<String> diseaseStates() {
        return jdbc.getJdbcTemplate().queryForList(
                "select disease_state from medications "
                        + "union select disease_state from patients order by 1",
                String.class);
    }
}
