package com.shields.healthrx.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.CommonDtos.NoteItem;
import com.shields.healthrx.web.dto.CommonDtos.TaskSummary;
import com.shields.healthrx.web.dto.ReferralDtos.StatusHistoryItem;

/** All referral, status-history, and referral-note persistence (reads and writes). */
@Repository
public class ReferralRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReferralRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Row projections ---

    public record QueueRow(
            UUID id, String referralNumber, UUID patientId, String patientName, String patientDisease,
            UUID clinicId, String clinicName, UUID medicationId, String medicationName,
            UUID payerId, String payerName, UUID ownerId, String ownerName,
            String currentStatus, String priority, Instant receivedAt,
            Instant paSubmittedAt, Instant paDecidedAt, Instant activeTherapyAt,
            BigDecimal copayAmount, BigDecimal financialAssistanceSecuredAmount,
            long openTaskCount, UUID therapyId, String therapyStatus) {
    }

    public record DetailRow(
            UUID id, String referralNumber, UUID patientId, String patientName, LocalDate patientDob,
            String patientDisease, UUID clinicId, String clinicName, UUID medicationId, String medicationName,
            String medicationRoute, UUID payerId, String payerName, String payerType, UUID ownerId,
            String ownerName, String currentStatus, String priority, Instant receivedAt,
            Instant benefitsInvestigationStartedAt, Instant paSubmittedAt, Instant paDecidedAt,
            Instant readyToFillAt, Instant deliveryScheduledAt, Instant activeTherapyAt,
            BigDecimal copayAmount, boolean financialAssistanceRequired,
            BigDecimal financialAssistanceSecuredAmount, UUID therapyId) {
    }

    /** Mutable referral state needed by the transition / financials services. */
    public record State(
            String currentStatus, UUID therapyId,
            Instant benefitsInvestigationStartedAt, Instant paSubmittedAt, Instant paDecidedAt,
            Instant readyToFillAt, Instant deliveryScheduledAt, Instant activeTherapyAt, Instant closedAt,
            boolean financialAssistanceRequired, BigDecimal financialAssistanceSecuredAmount,
            BigDecimal copayAmount) {
    }

    public record StatusHistoryInsert(
            UUID id, UUID referralId, String fromStatus, String toStatus, Instant changedAt,
            UUID changedById, String note, String phase2EventType) {
    }

    // --- Queue ---

    private static final Map<String, String> SORTABLE = Map.of(
            "receivedAt", "r.received_at",
            "copayAmount", "r.copay_amount",
            "referralNumber", "r.referral_number",
            "currentStatus", "r.current_status",
            "priority", "case r.priority when 'URGENT' then 4 when 'HIGH' then 3 when 'MEDIUM' then 2 else 1 end",
            "patient", "p.first_name || ' ' || p.last_name",
            "medication", "m.name",
            "owner", "ct.display_name",
            "openTaskCount", "open_task_count");

    public long countQueue(QueueFilter f) {
        StringBuilder sql = new StringBuilder(
                "select count(*) from referrals r join patients p on p.id = r.patient_id where 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        applyFilters(f, sql, params);
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public List<QueueRow> queue(QueueFilter f) {
        StringBuilder sql = new StringBuilder("""
                select r.id, r.referral_number, p.id as patient_id,
                       p.first_name || ' ' || p.last_name as patient_name, p.disease_state as patient_disease,
                       c.id as clinic_id, c.name as clinic_name,
                       m.id as medication_id, m.name as medication_name,
                       pay.id as payer_id, pay.name as payer_name,
                       ct.id as owner_id, ct.display_name as owner_name,
                       r.current_status, r.priority, r.received_at,
                       r.pa_submitted_at, r.pa_decided_at, r.active_therapy_at,
                       r.copay_amount, r.financial_assistance_secured_amount,
                       (select count(*) from tasks t where t.referral_id = r.id
                            and t.status in ('OPEN','IN_PROGRESS')) as open_task_count,
                       r.therapy_id, th.status as therapy_status
                from referrals r
                join patients p on p.id = r.patient_id
                join clinics c on c.id = r.clinic_id
                join medications m on m.id = r.medication_id
                join payers pay on pay.id = r.payer_id
                join care_team_members ct on ct.id = r.owner_id
                left join therapies th on th.id = r.therapy_id
                where 1=1""");
        MapSqlParameterSource params = new MapSqlParameterSource();
        applyFilters(f, sql, params);

        String sortCol = SORTABLE.getOrDefault(f.sortField(), "r.received_at");
        String dir = "asc".equalsIgnoreCase(f.sortDirection()) ? "asc" : "desc";
        sql.append(" order by ").append(sortCol).append(' ').append(dir)
                .append(", r.received_at desc");

        int size = Math.min(Math.max(f.size(), 1), 100);
        int offset = Math.max(f.page(), 0) * size;
        sql.append(" limit :limit offset :offset");
        params.addValue("limit", size).addValue("offset", offset);

        return jdbc.query(sql.toString(), params, (rs, i) -> new QueueRow(
                Columns.uuid(rs, "id"), rs.getString("referral_number"), Columns.uuid(rs, "patient_id"),
                rs.getString("patient_name"), rs.getString("patient_disease"),
                Columns.uuid(rs, "clinic_id"), rs.getString("clinic_name"),
                Columns.uuid(rs, "medication_id"), rs.getString("medication_name"),
                Columns.uuid(rs, "payer_id"), rs.getString("payer_name"),
                Columns.uuid(rs, "owner_id"), rs.getString("owner_name"),
                rs.getString("current_status"), rs.getString("priority"), Columns.instant(rs, "received_at"),
                Columns.instant(rs, "pa_submitted_at"), Columns.instant(rs, "pa_decided_at"),
                Columns.instant(rs, "active_therapy_at"), Columns.money(rs, "copay_amount"),
                Columns.money(rs, "financial_assistance_secured_amount"), rs.getLong("open_task_count"),
                Columns.uuid(rs, "therapy_id"), rs.getString("therapy_status")));
    }

    private void applyFilters(QueueFilter f, StringBuilder sql, MapSqlParameterSource params) {
        if (f.status() != null) {
            sql.append(" and r.current_status = :status");
            params.addValue("status", f.status());
        } else if (!f.includeCancelled()) {
            sql.append(" and r.current_status <> 'CANCELLED'");
        }
        if (f.clinicId() != null) {
            sql.append(" and r.clinic_id = :clinicId");
            params.addValue("clinicId", f.clinicId());
        }
        if (f.diseaseState() != null) {
            sql.append(" and p.disease_state = :diseaseState");
            params.addValue("diseaseState", f.diseaseState());
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
        if (f.priority() != null) {
            sql.append(" and r.priority = :priority");
            params.addValue("priority", f.priority());
        }
        if (f.search() != null && !f.search().isBlank()) {
            sql.append(" and (p.first_name || ' ' || p.last_name ilike :search"
                    + " or r.referral_number ilike :search)");
            params.addValue("search", "%" + f.search().trim() + "%");
        }
    }

    // --- Detail ---

    public Optional<DetailRow> findDetail(UUID id) {
        String sql = """
                select r.id, r.referral_number, p.id as patient_id,
                       p.first_name || ' ' || p.last_name as patient_name, p.date_of_birth as patient_dob,
                       p.disease_state as patient_disease, c.id as clinic_id, c.name as clinic_name,
                       m.id as medication_id, m.name as medication_name, m.route as medication_route,
                       pay.id as payer_id, pay.name as payer_name, pay.payer_type as payer_type,
                       ct.id as owner_id, ct.display_name as owner_name,
                       r.current_status, r.priority, r.received_at,
                       r.benefits_investigation_started_at, r.pa_submitted_at, r.pa_decided_at,
                       r.ready_to_fill_at, r.delivery_scheduled_at, r.active_therapy_at,
                       r.copay_amount, r.financial_assistance_required,
                       r.financial_assistance_secured_amount, r.therapy_id
                from referrals r
                join patients p on p.id = r.patient_id
                join clinics c on c.id = r.clinic_id
                join medications m on m.id = r.medication_id
                join payers pay on pay.id = r.payer_id
                join care_team_members ct on ct.id = r.owner_id
                where r.id = :id""";
        var rows = jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, i) -> new DetailRow(
                Columns.uuid(rs, "id"), rs.getString("referral_number"), Columns.uuid(rs, "patient_id"),
                rs.getString("patient_name"), Columns.localDate(rs, "patient_dob"), rs.getString("patient_disease"),
                Columns.uuid(rs, "clinic_id"), rs.getString("clinic_name"), Columns.uuid(rs, "medication_id"),
                rs.getString("medication_name"), rs.getString("medication_route"), Columns.uuid(rs, "payer_id"),
                rs.getString("payer_name"), rs.getString("payer_type"), Columns.uuid(rs, "owner_id"),
                rs.getString("owner_name"), rs.getString("current_status"), rs.getString("priority"),
                Columns.instant(rs, "received_at"), Columns.instant(rs, "benefits_investigation_started_at"),
                Columns.instant(rs, "pa_submitted_at"), Columns.instant(rs, "pa_decided_at"),
                Columns.instant(rs, "ready_to_fill_at"), Columns.instant(rs, "delivery_scheduled_at"),
                Columns.instant(rs, "active_therapy_at"), Columns.money(rs, "copay_amount"),
                rs.getBoolean("financial_assistance_required"),
                Columns.money(rs, "financial_assistance_secured_amount"), Columns.uuid(rs, "therapy_id")));
        return rows.stream().findFirst();
    }

    public List<StatusHistoryItem> statusHistory(UUID referralId) {
        String sql = """
                select h.id, h.from_status, h.to_status, h.changed_at, h.note,
                       ct.id as actor_id, ct.display_name as actor_name
                from referral_status_history h
                left join care_team_members ct on ct.id = h.changed_by_id
                where h.referral_id = :id
                order by h.changed_at asc""";
        return jdbc.query(sql, new MapSqlParameterSource("id", referralId), (rs, i) -> new StatusHistoryItem(
                Columns.uuid(rs, "id"), rs.getString("from_status"), rs.getString("to_status"),
                Columns.instant(rs, "changed_at"), actorRef(rs), rs.getString("note")));
    }

    public List<NoteItem> recentNotes(UUID referralId, int limit) {
        String sql = """
                select n.id, n.body, n.created_at, ct.id as author_id, ct.display_name as author_name
                from referral_notes n
                join care_team_members ct on ct.id = n.author_id
                where n.referral_id = :id
                order by n.created_at desc
                limit :limit""";
        var params = new MapSqlParameterSource("id", referralId).addValue("limit", limit);
        return jdbc.query(sql, params, (rs, i) -> new NoteItem(
                Columns.uuid(rs, "id"),
                new NamedRef(Columns.uuid(rs, "author_id"), rs.getString("author_name")),
                rs.getString("body"), Columns.instant(rs, "created_at")));
    }

    public List<TaskSummary> openTasksForReferral(UUID referralId) {
        String sql = """
                select id, type, status, priority, title, due_at
                from tasks
                where referral_id = :id and status in ('OPEN','IN_PROGRESS')
                order by due_at asc nulls last""";
        return jdbc.query(sql, new MapSqlParameterSource("id", referralId), ReferralRepository::taskSummary);
    }

    // --- Writes ---

    /** The referral's owning care-team member (assignee for agent-created tasks). */
    public Optional<UUID> ownerOf(UUID id) {
        return jdbc.query("select owner_id from referrals where id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> Columns.uuid(rs, "owner_id"))
                .stream().findFirst();
    }

    public Optional<UUID> patientOf(UUID id) {
        return jdbc.query("select patient_id from referrals where id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> Columns.uuid(rs, "patient_id"))
                .stream().findFirst();
    }

    /** A patient never carries two referrals for the same medication (any status). */
    public boolean existsForPatientAndMedication(UUID patientId, UUID medicationId) {
        Long n = jdbc.queryForObject(
                "select count(*) from referrals where patient_id = :pid and medication_id = :mid",
                new MapSqlParameterSource().addValue("pid", patientId).addValue("mid", medicationId),
                Long.class);
        return n != null && n > 0;
    }

    public Optional<State> loadState(UUID id) {
        String sql = """
                select current_status, therapy_id, benefits_investigation_started_at, pa_submitted_at,
                       pa_decided_at, ready_to_fill_at, delivery_scheduled_at, active_therapy_at, closed_at,
                       financial_assistance_required, financial_assistance_secured_amount, copay_amount
                from referrals where id = :id""";
        var rows = jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, i) -> new State(
                rs.getString("current_status"), Columns.uuid(rs, "therapy_id"),
                Columns.instant(rs, "benefits_investigation_started_at"), Columns.instant(rs, "pa_submitted_at"),
                Columns.instant(rs, "pa_decided_at"), Columns.instant(rs, "ready_to_fill_at"),
                Columns.instant(rs, "delivery_scheduled_at"), Columns.instant(rs, "active_therapy_at"),
                Columns.instant(rs, "closed_at"), rs.getBoolean("financial_assistance_required"),
                Columns.money(rs, "financial_assistance_secured_amount"), Columns.money(rs, "copay_amount")));
        return rows.stream().findFirst();
    }

    public void updateState(UUID id, State s, Instant updatedAt) {
        String sql = """
                update referrals set
                    current_status = :currentStatus,
                    benefits_investigation_started_at = :benefits,
                    pa_submitted_at = :paSubmitted,
                    pa_decided_at = :paDecided,
                    ready_to_fill_at = :readyToFill,
                    delivery_scheduled_at = :deliveryScheduled,
                    active_therapy_at = :activeTherapy,
                    closed_at = :closedAt,
                    financial_assistance_required = :faRequired,
                    financial_assistance_secured_amount = :faSecured,
                    copay_amount = :copay,
                    updated_at = :updatedAt
                where id = :id""";
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("currentStatus", s.currentStatus())
                .addValue("benefits", Columns.ts(s.benefitsInvestigationStartedAt()))
                .addValue("paSubmitted", Columns.ts(s.paSubmittedAt()))
                .addValue("paDecided", Columns.ts(s.paDecidedAt()))
                .addValue("readyToFill", Columns.ts(s.readyToFillAt()))
                .addValue("deliveryScheduled", Columns.ts(s.deliveryScheduledAt()))
                .addValue("activeTherapy", Columns.ts(s.activeTherapyAt()))
                .addValue("closedAt", Columns.ts(s.closedAt()))
                .addValue("faRequired", s.financialAssistanceRequired())
                .addValue("faSecured", s.financialAssistanceSecuredAmount())
                .addValue("copay", s.copayAmount())
                .addValue("updatedAt", Columns.ts(updatedAt));
        jdbc.update(sql, params);
    }

    public void insertStatusHistory(StatusHistoryInsert h) {
        String sql = """
                insert into referral_status_history
                    (id, referral_id, from_status, to_status, changed_at, changed_by_id, note, phase2_event_type)
                values (:id, :referralId, :fromStatus, :toStatus, :changedAt, :changedById, :note, :event)""";
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", h.id())
                .addValue("referralId", h.referralId())
                .addValue("fromStatus", h.fromStatus())
                .addValue("toStatus", h.toStatus())
                .addValue("changedAt", Columns.ts(h.changedAt()))
                .addValue("changedById", h.changedById())
                .addValue("note", h.note())
                .addValue("event", h.phase2EventType());
        jdbc.update(sql, params);
    }

    public void insertNote(UUID id, UUID referralId, UUID authorId, String body, Instant createdAt) {
        String sql = """
                insert into referral_notes (id, referral_id, author_id, body, created_at)
                values (:id, :referralId, :authorId, :body, :createdAt)""";
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id).addValue("referralId", referralId).addValue("authorId", authorId)
                .addValue("body", body).addValue("createdAt", Columns.ts(createdAt));
        jdbc.update(sql, params);
    }

    public boolean exists(UUID id) {
        Long n = jdbc.queryForObject("select count(*) from referrals where id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        return n != null && n > 0;
    }

    /** Links a (newly auto-created) therapy to a referral. */
    public void linkTherapy(UUID referralId, UUID therapyId) {
        jdbc.update("update referrals set therapy_id = :tid where id = :rid",
                new MapSqlParameterSource("rid", referralId).addValue("tid", therapyId));
    }

    /** Next demo-friendly referral number, e.g. RX-10109 after RX-10108. */
    public String nextReferralNumber() {
        Integer maxNum = jdbc.queryForObject("""
                select coalesce(max(cast(substring(referral_number from 4) as integer)), 10000)
                from referrals where referral_number ~ '^RX-[0-9]+$'""",
                new MapSqlParameterSource(), Integer.class);
        return "RX-" + ((maxNum == null ? 10000 : maxNum) + 1);
    }

    /** Inserts a new referral in ELIGIBILITY_IDENTIFIED. */
    public void insertReferral(UUID id, String referralNumber, UUID patientId, UUID clinicId, UUID medicationId,
            UUID payerId, UUID ownerId, String priority, Instant receivedAt, boolean paRequired,
            boolean financialAssistanceRequired, Instant createdAt) {
        jdbc.update("""
                insert into referrals (id, referral_number, patient_id, clinic_id, medication_id, payer_id,
                    owner_id, current_status, priority, received_at, pa_required, financial_assistance_required,
                    financial_assistance_secured_amount, copay_amount, created_at, updated_at)
                values (:id, :num, :pid, :cid, :mid, :payid, :owner, 'ELIGIBILITY_IDENTIFIED', :priority,
                    :received, :pa, :fa, 0, 0, :created, :created)""",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("num", referralNumber).addValue("pid", patientId)
                        .addValue("cid", clinicId).addValue("mid", medicationId).addValue("payid", payerId)
                        .addValue("owner", ownerId).addValue("priority", priority)
                        .addValue("received", Columns.ts(receivedAt)).addValue("pa", paRequired)
                        .addValue("fa", financialAssistanceRequired).addValue("created", Columns.ts(createdAt)));
    }

    /** Updates only the financial columns (no status history row); used alongside a transition. */
    public void updateFinancialAmounts(UUID id, BigDecimal copayAmount, BigDecimal securedAmount,
            Boolean required, Instant updatedAt) {
        jdbc.update("""
                update referrals set
                    copay_amount = coalesce(:copay, copay_amount),
                    financial_assistance_secured_amount = coalesce(:secured, financial_assistance_secured_amount),
                    financial_assistance_required = coalesce(:required, financial_assistance_required),
                    updated_at = :updated
                where id = :id""",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("copay", copayAmount).addValue("secured", securedAmount)
                        .addValue("required", required).addValue("updated", Columns.ts(updatedAt)));
    }

    private static NamedRef actorRef(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID actorId = Columns.uuid(rs, "actor_id");
        return actorId == null ? null : new NamedRef(actorId, rs.getString("actor_name"));
    }

    static TaskSummary taskSummary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TaskSummary(Columns.uuid(rs, "id"), rs.getString("type"), rs.getString("status"),
                rs.getString("priority"), rs.getString("title"), Columns.instant(rs, "due_at"));
    }
}
