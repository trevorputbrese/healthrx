package com.healthrx.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.healthrx.web.dto.CommonDtos.NamedRef;
import com.healthrx.web.dto.DashboardDtos.OwnerTaskCount;
import com.healthrx.web.dto.DashboardDtos.StatusCount;

/**
 * Dashboard aggregate queries. All date/time comparisons use values derived from the
 * application clock (passed as params) — never the database's current_date/now().
 */
@Repository
public class DashboardRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FinancialResult(BigDecimal amount, long count) {
    }

    // --- Therapy-based metrics ---

    public long activePatientsOnTherapy(DashboardFilter f, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                select count(distinct t.patient_id)
                from therapies t join patients p on p.id = t.patient_id
                where t.status = 'ACTIVE' and (t.start_date is null or t.start_date <= :today)
                  and (t.end_date is null or t.end_date > :today)""");
        MapSqlParameterSource params = new MapSqlParameterSource("today", today);
        appendTherapyFilters(f, sql, params);
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public List<UUID> activeTherapyIds(DashboardFilter f, LocalDate today) {
        StringBuilder sql = new StringBuilder("""
                select t.id from therapies t join patients p on p.id = t.patient_id
                where t.status = 'ACTIVE' and (t.start_date is null or t.start_date <= :today)
                  and (t.end_date is null or t.end_date > :today)""");
        MapSqlParameterSource params = new MapSqlParameterSource("today", today);
        appendTherapyFilters(f, sql, params);
        return jdbc.query(sql.toString(), params, (rs, i) -> Columns.uuid(rs, "id"));
    }

    // --- Referral-based windowed metrics ---

    public List<Double> timeToTherapyDays(DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select extract(epoch from (r.active_therapy_at - r.received_at)) / 86400.0 as days
                from referrals r join patients p on p.id = r.patient_id
                where r.active_therapy_at is not null
                  and r.active_therapy_at >= :from and r.active_therapy_at < :to""");
        MapSqlParameterSource params = windowParams(f);
        appendReferralFilters(f, sql, params);
        return jdbc.queryForList(sql.toString(), params, Double.class);
    }

    public List<Double> paTurnaroundDays(DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select extract(epoch from (r.pa_decided_at - r.pa_submitted_at)) / 86400.0 as days
                from referrals r join patients p on p.id = r.patient_id
                where r.pa_submitted_at is not null and r.pa_decided_at is not null
                  and r.pa_decided_at >= :from and r.pa_decided_at < :to""");
        MapSqlParameterSource params = windowParams(f);
        appendReferralFilters(f, sql, params);
        return jdbc.queryForList(sql.toString(), params, Double.class);
    }

    public FinancialResult financialAssistance(DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select coalesce(sum(r.financial_assistance_secured_amount), 0) as amount, count(*) as cnt
                from referrals r join patients p on p.id = r.patient_id
                where r.financial_assistance_secured_amount > 0
                  and coalesce(r.ready_to_fill_at, r.active_therapy_at, r.updated_at) >= :from
                  and coalesce(r.ready_to_fill_at, r.active_therapy_at, r.updated_at) < :to""");
        MapSqlParameterSource params = windowParams(f);
        appendReferralFilters(f, sql, params);
        return jdbc.queryForObject(sql.toString(), params, (rs, i) ->
                new FinancialResult(rs.getBigDecimal("amount"), rs.getLong("cnt")));
    }

    public List<StatusCount> statusCounts(DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select r.current_status as status, count(*) as cnt
                from referrals r join patients p on p.id = r.patient_id
                where r.current_status <> 'CANCELLED'""");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendReferralFilters(f, sql, params);
        sql.append(" group by r.current_status order by r.current_status");
        return jdbc.query(sql.toString(), params,
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("cnt")));
    }

    // --- Task metrics ---

    public long overdueTaskCount(DashboardFilter f, Instant now) {
        StringBuilder sql = new StringBuilder("""
                select count(*) from tasks tk join patients p on p.id = tk.patient_id
                where tk.status in ('OPEN','IN_PROGRESS') and tk.due_at is not null and tk.due_at < :now""");
        MapSqlParameterSource params = new MapSqlParameterSource("now", Columns.ts(now));
        appendTaskFilters(f, sql, params);
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public List<OwnerTaskCount> openTasksByOwner(DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select ct.id as owner_id, ct.display_name as owner_name, count(*) as cnt
                from tasks tk join patients p on p.id = tk.patient_id
                join care_team_members ct on ct.id = tk.owner_id
                where tk.status in ('OPEN','IN_PROGRESS')""");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTaskFilters(f, sql, params);
        sql.append(" group by ct.id, ct.display_name order by cnt desc, ct.display_name");
        return jdbc.query(sql.toString(), params, (rs, i) -> new OwnerTaskCount(
                new NamedRef(Columns.uuid(rs, "owner_id"), rs.getString("owner_name")), rs.getLong("cnt")));
    }

    // --- Trend helpers (windowed counts/medians per bucket) ---

    public long countReferralsReceived(OffsetDateTime from, OffsetDateTime to, DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select count(*) from referrals r join patients p on p.id = r.patient_id
                where r.received_at >= :from and r.received_at < :to""");
        MapSqlParameterSource params = new MapSqlParameterSource("from", from).addValue("to", to);
        appendReferralFilters(f, sql, params);
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public long countActivatedTherapies(OffsetDateTime from, OffsetDateTime to, DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select count(*) from referrals r join patients p on p.id = r.patient_id
                where r.active_therapy_at >= :from and r.active_therapy_at < :to""");
        MapSqlParameterSource params = new MapSqlParameterSource("from", from).addValue("to", to);
        appendReferralFilters(f, sql, params);
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public List<Double> timeToTherapyDaysBetween(OffsetDateTime from, OffsetDateTime to, DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select extract(epoch from (r.active_therapy_at - r.received_at)) / 86400.0 as days
                from referrals r join patients p on p.id = r.patient_id
                where r.active_therapy_at is not null and r.active_therapy_at >= :from and r.active_therapy_at < :to""");
        MapSqlParameterSource params = new MapSqlParameterSource("from", from).addValue("to", to);
        appendReferralFilters(f, sql, params);
        return jdbc.queryForList(sql.toString(), params, Double.class);
    }

    public List<Double> paTurnaroundDaysBetween(OffsetDateTime from, OffsetDateTime to, DashboardFilter f) {
        StringBuilder sql = new StringBuilder("""
                select extract(epoch from (r.pa_decided_at - r.pa_submitted_at)) / 86400.0 as days
                from referrals r join patients p on p.id = r.patient_id
                where r.pa_submitted_at is not null and r.pa_decided_at is not null
                  and r.pa_decided_at >= :from and r.pa_decided_at < :to""");
        MapSqlParameterSource params = new MapSqlParameterSource("from", from).addValue("to", to);
        appendReferralFilters(f, sql, params);
        return jdbc.queryForList(sql.toString(), params, Double.class);
    }

    // --- filter clause builders ---

    private void appendReferralFilters(DashboardFilter f, StringBuilder sql, MapSqlParameterSource params) {
        if (f.clinicId() != null) {
            sql.append(" and r.clinic_id = :clinicId");
            params.addValue("clinicId", f.clinicId());
        }
        if (f.payerId() != null) {
            sql.append(" and r.payer_id = :payerId");
            params.addValue("payerId", f.payerId());
        }
        if (f.medicationId() != null) {
            sql.append(" and r.medication_id = :medicationId");
            params.addValue("medicationId", f.medicationId());
        }
        if (f.ownerId() != null) {
            sql.append(" and r.owner_id = :ownerId");
            params.addValue("ownerId", f.ownerId());
        }
        if (f.diseaseState() != null) {
            sql.append(" and p.disease_state = :diseaseState");
            params.addValue("diseaseState", f.diseaseState());
        }
    }

    private void appendTherapyFilters(DashboardFilter f, StringBuilder sql, MapSqlParameterSource params) {
        if (f.clinicId() != null) {
            sql.append(" and p.clinic_id = :clinicId");
            params.addValue("clinicId", f.clinicId());
        }
        if (f.payerId() != null) {
            sql.append(" and p.payer_id = :payerId");
            params.addValue("payerId", f.payerId());
        }
        if (f.ownerId() != null) {
            sql.append(" and p.primary_owner_id = :ownerId");
            params.addValue("ownerId", f.ownerId());
        }
        if (f.medicationId() != null) {
            sql.append(" and t.medication_id = :medicationId");
            params.addValue("medicationId", f.medicationId());
        }
        if (f.diseaseState() != null) {
            sql.append(" and t.disease_state = :diseaseState");
            params.addValue("diseaseState", f.diseaseState());
        }
    }

    private void appendTaskFilters(DashboardFilter f, StringBuilder sql, MapSqlParameterSource params) {
        if (f.clinicId() != null) {
            sql.append(" and p.clinic_id = :clinicId");
            params.addValue("clinicId", f.clinicId());
        }
        if (f.payerId() != null) {
            sql.append(" and p.payer_id = :payerId");
            params.addValue("payerId", f.payerId());
        }
        if (f.ownerId() != null) {
            sql.append(" and tk.owner_id = :ownerId");
            params.addValue("ownerId", f.ownerId());
        }
        if (f.diseaseState() != null) {
            sql.append(" and p.disease_state = :diseaseState");
            params.addValue("diseaseState", f.diseaseState());
        }
    }

    private MapSqlParameterSource windowParams(DashboardFilter f) {
        return new MapSqlParameterSource()
                .addValue("from", f.from().atStartOfDay().atOffset(ZoneOffset.UTC))
                .addValue("to", f.to().atStartOfDay().atOffset(ZoneOffset.UTC));
    }
}
