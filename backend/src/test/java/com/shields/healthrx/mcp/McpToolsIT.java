package com.shields.healthrx.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shields.healthrx.domain.AgentName;
import com.shields.healthrx.web.ApiException;

/**
 * The embedded MCP action tools against a real Postgres (phase-3-design.md §13): the
 * agent_tool_calls ledger makes each tool exactly-once per recommendation (replay returns the
 * stored result, no duplicate domain rows), record_prescription_fill rolls the canonical refill
 * due date, and call-time identity scoping rejects missing/out-of-scope identities.
 */
@SpringBootTest(properties = {
        "healthrx.events.consumer.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class McpToolsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    HealthRxActionTools tools;
    @Autowired
    JdbcTemplate jdbc;

    UUID patientId;
    UUID referralId;
    UUID therapyId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from agent_tool_calls");
        var row = jdbc.queryForMap(
                "select id, patient_id, therapy_id from referrals where therapy_id is not null limit 1");
        referralId = (UUID) row.get("id");
        patientId = (UUID) row.get("patient_id");
        therapyId = (UUID) row.get("therapy_id");
    }

    @AfterEach
    void clearIdentity() {
        AgentCallContext.clear();
    }

    @Test
    void toolsRequireAValidAgentIdentity() {
        AgentCallContext.clear();
        assertThatThrownBy(() -> tools.logOutreach(UUID.randomUUID().toString(), patientId.toString(),
                null, "PHONE", "REACHED", "hi"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("AGENT_IDENTITY_REQUIRED"));
    }

    @Test
    void toolScopingRejectsOutOfScopeAgents() {
        AgentCallContext.set(AgentName.ACCESS_WORKFLOW);
        assertThatThrownBy(() -> tools.recordPrescriptionFill(UUID.randomUUID().toString(),
                therapyId.toString(), 30, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("TOOL_NOT_ALLOWED"));

        AgentCallContext.set(AgentName.ADHERENCE_RISK);
        assertThatThrownBy(() -> tools.createTask(UUID.randomUUID().toString(), patientId.toString(),
                referralId.toString(), null, "t", "d", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("TOOL_NOT_ALLOWED"));
    }

    @Test
    void logOutreachIsExactlyOncePerRecommendation() {
        AgentCallContext.set(AgentName.ADHERENCE_RISK);
        UUID rec = UUID.randomUUID();
        long before = count("outreach_events");

        String first = tools.logOutreach(rec.toString(), patientId.toString(), referralId.toString(),
                "PHONE", "REACHED", "script text");
        String replay = tools.logOutreach(rec.toString(), patientId.toString(), referralId.toString(),
                "PHONE", "REACHED", "script text");

        assertThat(replay).isEqualTo(first);
        assertThat(count("outreach_events")).isEqualTo(before + 1);
        // The clinical record is attributed to the agent's care-team actor.
        assertThat(jdbc.queryForObject("""
                select count(*) from outreach_events where owner_id = ?""",
                Integer.class, AgentName.ADHERENCE_RISK.actorId())).isEqualTo(1);
    }

    @Test
    void recordFillRollsDueDateAndReplaysSafely() {
        AgentCallContext.set(AgentName.ADHERENCE_RISK);
        UUID rec = UUID.randomUUID();
        long fillsBefore = count("fills");

        tools.recordPrescriptionFill(rec.toString(), therapyId.toString(), 30, "2026-06-29");
        tools.recordPrescriptionFill(rec.toString(), therapyId.toString(), 30, "2026-06-29");

        assertThat(count("fills")).isEqualTo(fillsBefore + 1);
        LocalDate due = jdbc.queryForObject(
                "select current_refill_due_date from therapies where id = ?", LocalDate.class, therapyId);
        assertThat(due).isEqualTo(LocalDate.parse("2026-07-29"));
    }

    @Test
    void recordPriorAuthDecisionIsAccessOnly() {
        AgentCallContext.set(AgentName.ADHERENCE_RISK);
        assertThatThrownBy(() -> tools.recordPriorAuthDecision(UUID.randomUUID().toString(),
                referralId.toString(), "APPROVED", "CP-1", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("TOOL_NOT_ALLOWED"));
    }

    @Test
    void recordPriorAuthDecisionAdvancesASubmittedReferralExactlyOnce() {
        AgentCallContext.set(AgentName.ACCESS_WORKFLOW);
        jdbc.update("""
                update referrals set current_status = 'PRIOR_AUTH_SUBMITTED',
                       pa_submitted_at = now(), pa_decided_at = null where id = ?""", referralId);
        UUID rec = UUID.randomUUID();

        String first = tools.recordPriorAuthDecision(rec.toString(), referralId.toString(),
                "APPROVED", "CP-42XYZ", "Approved by ClearPath in 1.1s");
        String replay = tools.recordPriorAuthDecision(rec.toString(), referralId.toString(),
                "APPROVED", "CP-42XYZ", "Approved by ClearPath in 1.1s");

        assertThat(replay).isEqualTo(first);
        assertThat(first).contains("\"applied\": true").contains("PRIOR_AUTH_APPROVED");
        assertThat(jdbc.queryForObject("select current_status from referrals where id = ?",
                String.class, referralId)).isEqualTo("PRIOR_AUTH_APPROVED");
        // The transition is attributed to the agent's care-team actor in the status history.
        assertThat(jdbc.queryForObject("""
                select count(*) from referral_status_history
                where referral_id = ? and to_status = 'PRIOR_AUTH_APPROVED' and changed_by_id = ?""",
                Integer.class, referralId, AgentName.ACCESS_WORKFLOW.actorId())).isEqualTo(1);
    }

    @Test
    void recordPriorAuthDecisionIsABenignNoOpWhenTheReferralMovedOn() {
        AgentCallContext.set(AgentName.ACCESS_WORKFLOW);
        jdbc.update("update referrals set current_status = 'READY_TO_FILL' where id = ?", referralId);

        String result = tools.recordPriorAuthDecision(UUID.randomUUID().toString(),
                referralId.toString(), "DENIED", null, null);

        assertThat(result).contains("\"applied\": false").contains("READY_TO_FILL");
        assertThat(jdbc.queryForObject("select current_status from referrals where id = ?",
                String.class, referralId)).isEqualTo("READY_TO_FILL");
    }

    @Test
    void createTaskAssignsReferralOwnerWithAgentPrefixAndDeterministicId() {
        AgentCallContext.set(AgentName.ACCESS_WORKFLOW);
        UUID rec = UUID.randomUUID();

        String first = tools.createTask(rec.toString(), patientId.toString(), referralId.toString(),
                "HIGH", "Chase the payer", "PA pending 6 days; call the payer.", null);
        String replay = tools.createTask(rec.toString(), patientId.toString(), referralId.toString(),
                "HIGH", "Chase the payer", "PA pending 6 days; call the payer.", null);
        assertThat(replay).isEqualTo(first);

        var task = jdbc.queryForMap("""
                select t.type, t.title, t.owner_id, r.owner_id as referral_owner
                from tasks t join referrals r on r.id = t.referral_id
                where t.type = 'ACCESS_FOLLOW_UP' and t.referral_id = ?""", referralId);
        assertThat(task.get("title").toString()).startsWith("[Agent] ");
        assertThat(task.get("owner_id")).isEqualTo(task.get("referral_owner"));
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }
}
