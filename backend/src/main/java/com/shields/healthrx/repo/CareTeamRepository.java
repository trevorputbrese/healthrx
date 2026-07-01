package com.shields.healthrx.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;

/** Care team member reads and actor validation (the Phase 1 owner/actor model). */
@Repository
public class CareTeamRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CareTeamRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<NamedRef> findRef(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        var rows = jdbc.query(
                "select id, display_name from care_team_members where id = :id",
                new MapSqlParameterSource("id", id),
                (rs, i) -> new NamedRef(Columns.uuid(rs, "id"), rs.getString("display_name")));
        return rows.stream().findFirst();
    }

    /** Resolves an actor id to a reference, requiring it to exist and be active. */
    public NamedRef requireActiveActor(UUID id) {
        var rows = jdbc.query(
                "select id, display_name from care_team_members where id = :id and active = true",
                new MapSqlParameterSource("id", id),
                (rs, i) -> new NamedRef(Columns.uuid(rs, "id"), rs.getString("display_name")));
        return rows.stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Care team member", id));
    }
}
