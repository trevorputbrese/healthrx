package com.healthrx.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrx.domain.WorkflowEventType;

/** End-to-end AMQP path: publish → consume → DB, plus idempotency and dead-lettering. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EventConsumerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Value("${healthrx.events.exchange}")
    String exchange;
    @Value("${healthrx.events.routing-prefix}")
    String routingPrefix;
    @Value("${healthrx.events.dlq}")
    String dlq;

    private void publish(EventEnvelope env) throws Exception {
        rabbit.convertAndSend(exchange, routingPrefix + env.eventType(), objectMapper.writeValueAsString(env));
    }

    private static void awaitUntil(BooleanSupplier cond, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Condition not met within " + seconds + "s");
    }

    private long processedCount(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Long.class, eventId);
    }

    @Test
    void consumesReferralCreatedAndIsIdempotent() throws Exception {
        UUID referralId = UUID.randomUUID();
        // A pair with no existing referral — the consumer skips duplicate patient+medication.
        var pair = jdbc.queryForMap("""
                select p.id as patient_id, m.id as medication_id
                from patients p join medications m on m.disease_state = p.disease_state and m.active
                where not exists (select 1 from referrals r
                                  where r.patient_id = p.id and r.medication_id = m.id)
                limit 1""");
        EventEnvelope env = new EventEnvelope(UUID.randomUUID(), WorkflowEventType.REFERRAL_CREATED.wireName(),
                Instant.parse("2026-07-02T10:00:00Z"), "test", Map.of(
                "referralId", referralId.toString(),
                "patientId", pair.get("patient_id").toString(),
                "clinicId", jdbc.queryForObject("select id from clinics limit 1", UUID.class).toString(),
                "medicationId", pair.get("medication_id").toString(),
                "payerId", jdbc.queryForObject("select id from payers limit 1", UUID.class).toString(),
                "ownerId", jdbc.queryForObject("select id from care_team_members where active limit 1", UUID.class).toString(),
                "priority", "MEDIUM",
                "paRequired", false));

        publish(env);
        awaitUntil(() -> processedCount(env.eventId()) == 1, 15);
        assertThat(jdbc.queryForObject("select count(*) from referrals where id = ?", Integer.class, referralId))
                .isEqualTo(1);

        // Duplicate delivery of the same eventId must be ignored (no PK violation, no second row).
        publish(env);
        Thread.sleep(1500);
        assertThat(processedCount(env.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from referrals where id = ?", Integer.class, referralId))
                .isEqualTo(1);
    }

    @Test
    void nonApplicableEventIsDeadLettered() throws Exception {
        // A transition for a referral that does not exist -> ApiException(404) -> dead-letter, not applied.
        EventEnvelope env = new EventEnvelope(UUID.randomUUID(), WorkflowEventType.READY_TO_FILL.wireName(),
                Instant.parse("2026-07-02T10:00:00Z"), "test",
                Map.of("referralId", UUID.randomUUID().toString()));
        publish(env);

        Message dead = rabbit.receive(dlq, 15000);
        assertThat(dead).as("message should be dead-lettered").isNotNull();
        assertThat(processedCount(env.eventId())).isZero();
    }
}
