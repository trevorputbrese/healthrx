package com.shields.healthrx.agent.adherence.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shields.healthrx.agent.adherence.core.AdherenceAgentService;

/** Senses the agent's trigger events off its narrow-bound durable queue. */
@Component
@ConditionalOnProperty(name = "healthrx.events.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class RefillMissedListener {

    private static final Logger log = LoggerFactory.getLogger(RefillMissedListener.class);

    private final AdherenceAgentService agent;
    private final ObjectMapper mapper;

    public RefillMissedListener(AdherenceAgentService agent, ObjectMapper mapper) {
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
            agent.onRefillMissed(env);
        } catch (Exception e) {
            // Guards + deterministic ids make a lost run safe; log loudly rather than requeue-loop.
            log.error("Agent run failed for trigger {}", env.eventId(), e);
        }
    }
}
