package com.healthrx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.healthrx.web.ApiException;
import com.healthrx.web.dto.TaskDtos;

/**
 * The Tasks page flows: list filters, complete/reopen with completed_at, transition guards, and
 * the task↔referral linkage in both directions (completion advances; advancing auto-completes).
 */
@SpringBootTest(properties = {
        "healthrx.events.consumer.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class TaskFlowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TaskService service;
    @Autowired
    ReferralService referralService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void listFiltersByStatusAndSearchesAcrossPatientAndReferral() {
        var open = service.list("OPEN_ALL", null, 0, 100);
        assertThat(open.items()).isNotEmpty()
                .allSatisfy(t -> assertThat(t.status()).isIn("OPEN", "IN_PROGRESS"));

        // Search by the patient name of the first open task narrows to that patient's tasks.
        TaskDtos.Item first = open.items().get(0);
        var byName = service.list(null, first.patient().displayName(), 0, 100);
        assertThat(byName.items()).isNotEmpty()
                .allSatisfy(t -> assertThat(t.patient().displayName()).isEqualTo(first.patient().displayName()));
    }

    @Test
    void completeStampsCompletedAtAndReopenClearsIt() {
        UUID id = jdbc.queryForObject("select id from tasks where status = 'OPEN' limit 1", UUID.class);

        TaskDtos.Item completed = service.updateStatus(id, "COMPLETED").task();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.completedAt()).isNotNull();

        TaskDtos.Item reopened = service.updateStatus(id, "OPEN").task();
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.completedAt()).isNull();
    }

    @Test
    void completingAccessFollowUpAdvancesItsReferral() {
        UUID referralId = jdbc.queryForObject(
                "select id from referrals where current_status = 'ELIGIBILITY_IDENTIFIED' limit 1", UUID.class);
        UUID taskId = insertAccessFollowUp(referralId, "[Agent] Review new referral");

        TaskDtos.StatusChangeResult result = service.updateStatus(taskId, "COMPLETED");

        assertThat(result.task().status()).isEqualTo("COMPLETED");
        assertThat(result.referralAdvance()).isNotNull();
        assertThat(result.referralAdvance().toStatus()).isEqualTo("BENEFITS_INVESTIGATION");
        assertThat(jdbc.queryForObject("select current_status from referrals where id = ?",
                String.class, referralId)).isEqualTo("BENEFITS_INVESTIGATION");
    }

    @Test
    void advancingAReferralAutoCompletesItsOpenTasks() {
        UUID referralId = jdbc.queryForObject(
                "select id from referrals where current_status = 'BENEFITS_INVESTIGATION' limit 1", UUID.class);
        UUID ownerId = jdbc.queryForObject(
                "select owner_id from referrals where id = ?", UUID.class, referralId);
        UUID taskId = insertAccessFollowUp(referralId, "[Agent] Verify coverage details");

        referralService.transition(referralId, "PRIOR_AUTH_SUBMITTED", ownerId, "PA submitted from the queue");

        assertThat(jdbc.queryForObject("select status from tasks where id = ?", String.class, taskId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select completed_at from tasks where id = ?",
                java.sql.Timestamp.class, taskId)).isNotNull();
    }

    private UUID insertAccessFollowUp(UUID referralId, String title) {
        jdbc.update("""
                insert into tasks (id, patient_id, referral_id, owner_id, type, status, priority, title, created_at)
                select gen_random_uuid(), patient_id, id, owner_id, 'ACCESS_FOLLOW_UP', 'OPEN', 'MEDIUM', ?, now()
                from referrals where id = ?""", title, referralId);
        return jdbc.queryForObject(
                "select id from tasks where referral_id = ? and title = ? order by created_at desc limit 1",
                UUID.class, referralId, title);
    }

    @Test
    void terminalTasksOnlyReopenAndNoOpTransitionsConflict() {
        UUID id = jdbc.queryForObject(
                "select id from tasks where status = 'OPEN' order by created_at desc limit 1", UUID.class);
        service.updateStatus(id, "CANCELLED");

        assertThatThrownBy(() -> service.updateStatus(id, "COMPLETED"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_TASK_TRANSITION"));
        assertThatThrownBy(() -> service.updateStatus(id, "CANCELLED"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_TASK_TRANSITION"));

        // Reopen is the one legal exit from a terminal status.
        assertThat(service.updateStatus(id, "OPEN").task().status()).isEqualTo("OPEN");
    }
}
