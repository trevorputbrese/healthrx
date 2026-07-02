package com.shields.healthrx.agent.access.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shields.healthrx.agent.access.core.AccessAgentService;

/** Senses new-referral triage triggers off the agent's narrow-bound durable queue. */
@Component
@ConditionalOnProperty(name = "healthrx.events.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ReferralCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ReferralCreatedListener.class);

    private final AccessAgentService agent;
    private final ObjectMapper mapper;

    public ReferralCreatedListener(AccessAgentService agent, ObjectMapper mapper) {
        this.agent = agent;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "${healthrx.events.queue}")
    public void onMessage(String body) {
        EventEnvelope env;
        try {
            env = mapper.readValue(body, EventEnvelope.class);
        } catch (Exception e) {
            log.warn("Unparseable event dropped: {}", body, e);
            return;
        }
        try {
            agent.onReferralCreated(env);
        } catch (Exception e) {
            // Deterministic ids + the emit-repair guard make a lost run safe; log, don't requeue-loop.
            log.error("Agent run failed for trigger {}", env.eventId(), e);
        }
    }
}
