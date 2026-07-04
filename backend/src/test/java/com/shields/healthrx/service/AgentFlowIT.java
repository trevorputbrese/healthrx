package com.shields.healthrx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shields.healthrx.web.ApiException;
import com.sun.net.httpserver.HttpServer;

/**
 * The recommendation lifecycle end-to-end against a real Postgres (phase-3-design.md §13):
 * consumer handlers (Created incl. supersede, Applied as state repair) and the hardened approve
 * flow (atomic gate, 409 double-approve, 502 + revert on unreachable agent, synchronous APPLIED,
 * APPLYING lazy re-arm) with a stubbed agent control API.
 */
@SpringBootTest(properties = {
        "healthrx.events.consumer.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class AgentFlowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** Stubbed agent control API; per-test behavior via {@link #agentResponds}. */
    static final HttpServer AGENT_STUB;
    static volatile boolean agentResponds = true;

    static {
        try {
            AGENT_STUB = HttpServer.create(new InetSocketAddress(0), 0);
            AGENT_STUB.createContext("/", exchange -> {
                byte[] body = "{\"applied\":true}".getBytes();
                int status = agentResponds ? 200 : 500;
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            AGENT_STUB.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopStub() {
        AGENT_STUB.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("healthrx.agents.adherence-risk.url",
                () -> "http://localhost:" + AGENT_STUB.getAddress().getPort());
        // Unroutable so the Access agent path exercises the 502 contract if ever hit.
        registry.add("healthrx.agents.access-workflow.url", () -> "http://localhost:1");
    }

    @Autowired
    AgentRecommendationService recommendations;
    @Autowired
    AgentOpsService ops;
    @Autowired
    JdbcTemplate jdbc;

    UUID patientId;
    UUID humanActorId;

    @BeforeEach
    void setUp() {
        agentResponds = true;
        jdbc.update("delete from agent_tool_calls");
        jdbc.update("delete from agent_recommendations");
        patientId = jdbc.queryForObject("select id from patients limit 1", UUID.class);
        // The single ACTIVE human (V8 collapses the care team; V9 renames them to the presenter).
        humanActorId = jdbc.queryForObject(
                "select id from care_team_members where active and role not in ('System','AI Agent') limit 1",
                UUID.class);
    }

    private UUID created(UUID recommendationId) {
        recommendations.recordCreated(Map.of(
                "recommendationId", recommendationId.toString(),
                "agentName", "adherence-risk",
                "patientId", patientId.toString(),
                "status", "PENDING",
                "summary", "test recommendation",
                "recommendation", Map.of("riskExplanation", "test"),
                "trace", java.util.List.of(Map.of("step", "trigger", "detail", "test"))),
                Instant.parse("2026-06-29T01:00:00Z"));
        return recommendationId;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from agent_recommendations where id = ?", String.class, id);
    }

    @Test
    void createdInsertsPendingAndDuplicateIsNoOp() {
        UUID id = created(UUID.randomUUID());
        assertThat(status(id)).isEqualTo("PENDING");
        created(id); // same recommendationId again — idempotent
        assertThat(jdbc.queryForObject("select count(*) from agent_recommendations", Integer.class)).isEqualTo(1);
    }

    @Test
    void newerRecommendationSupersedesOpenPending() {
        UUID first = created(UUID.randomUUID());
        UUID second = created(UUID.randomUUID());
        assertThat(status(first)).isEqualTo("SUPERSEDED");
        assertThat(status(second)).isEqualTo("PENDING");
    }

    @Test
    void appliedEventRepairsPendingAndNoOpsOnTerminal() {
        UUID id = created(UUID.randomUUID());
        recommendations.recordApplied(Map.of("recommendationId", id.toString()),
                Instant.parse("2026-06-29T02:00:00Z"));
        assertThat(status(id)).isEqualTo("APPLIED");

        // Terminal rows are untouched by a redelivered/late Applied.
        UUID dismissed = created(UUID.randomUUID());
        ops.dismiss(dismissed, humanActorId);
        recommendations.recordApplied(Map.of("recommendationId", dismissed.toString()), Instant.now());
        assertThat(status(dismissed)).isEqualTo("DISMISSED");
    }

    @Test
    void approveGatesProxiesAndMarksAppliedSynchronously() {
        UUID id = created(UUID.randomUUID());
        var result = ops.approve(id, humanActorId);
        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.decidedBy()).isNotNull();
        assertThat(status(id)).isEqualTo("APPLIED");

        // Double-approve (or approve after apply) is a 409, never a double-write.
        assertThatThrownBy(() -> ops.approve(id, humanActorId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unreachableAgentReturns502AndRevertsToPending() {
        agentResponds = false;
        UUID id = created(UUID.randomUUID());
        assertThatThrownBy(() -> ops.approve(id, humanActorId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "select decided_by_id from agent_recommendations where id = ?", UUID.class, id)).isNull();

        // Retry after the agent recovers succeeds.
        agentResponds = true;
        assertThat(ops.approve(id, humanActorId).status()).isEqualTo("APPLIED");
    }

    @Test
    void timedOutApplyingIsLazilyRearmedByTheFeed() {
        UUID id = created(UUID.randomUUID());
        jdbc.update("update agent_recommendations set status = 'APPLYING', applying_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(600)), id);

        ops.feed(null, null, 0, 20);
        assertThat(status(id)).isEqualTo("PENDING");
    }

    @Test
    void dismissRecordsDeciderAndConflictsWhenNotPending() {
        UUID id = created(UUID.randomUUID());
        var result = ops.dismiss(id, humanActorId);
        assertThat(result.status()).isEqualTo("DISMISSED");
        assertThatThrownBy(() -> ops.dismiss(id, humanActorId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
