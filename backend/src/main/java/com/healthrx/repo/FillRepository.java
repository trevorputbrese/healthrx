package com.healthrx.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fill writes for Phase 2 event consumption (PrescriptionFilled, RefillMissed). */
@Repository
public class FillRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FillRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int nextFillNumber(UUID therapyId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(fill_number), 0) from fills where therapy_id = :t",
                new MapSqlParameterSource("t", therapyId), Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    public void insert(UUID id, UUID patientId, UUID therapyId, UUID referralId, int fillNumber,
            String status, LocalDate dispensedAt, int daysSupply, LocalDate expectedRefillDate, Instant createdAt) {
        jdbc.update("""
                insert into fills (id, patient_id, therapy_id, referral_id, fill_number, status,
                                   dispensed_at, days_supply, expected_refill_date, created_at)
                values (:id, :pid, :tid, :rid, :num, :status, :dispensed, :days, :expected, :created)""",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("pid", patientId).addValue("tid", therapyId)
                        .addValue("rid", referralId).addValue("num", fillNumber).addValue("status", status)
                        .addValue("dispensed", dispensedAt).addValue("days", daysSupply)
                        .addValue("expected", expectedRefillDate).addValue("created", Columns.ts(createdAt)));
    }
}
