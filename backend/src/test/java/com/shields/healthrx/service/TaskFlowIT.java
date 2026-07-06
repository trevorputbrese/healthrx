package com.shields.healthrx.service;

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

import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.dto.TaskDtos;

/** The Tasks page flows: list filters, complete/reopen with completed_at, transition guards. */
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

        TaskDtos.Item completed = service.updateStatus(id, "COMPLETED");
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.completedAt()).isNotNull();

        TaskDtos.Item reopened = service.updateStatus(id, "OPEN");
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.completedAt()).isNull();
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
        assertThat(service.updateStatus(id, "OPEN").status()).isEqualTo("OPEN");
    }
}
