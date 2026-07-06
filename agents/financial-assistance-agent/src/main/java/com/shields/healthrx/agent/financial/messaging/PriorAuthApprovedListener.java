package com.shields.healthrx.agent.financial.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shields.healthrx.agent.financial.core.FinancialAssistanceAgentService;

/** Senses PriorAuthorizationApproved off the agent's narrow-bound durable queue. */
@Component
@ConditionalOnProperty(name = "healthrx.events.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PriorAuthApprovedListener {

    private static final Logger log = LoggerFactory.getLogger(PriorAuthApprovedListener.class);

    private final FinancialAssistanceAgentService agent;
    private final ObjectMapper mapper;

    public PriorAuthApprovedListener(FinancialAssistanceAgentService agent, ObjectMapper mapper) {
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
            agent.onPriorAuthorizationApproved(env);
        } catch (Exception e) {
            // Deterministic ids make a lost run safe to retry from the next PriorAuthorizationApproved
            // for the same referral (there won't be one) — log, don't requeue-loop.
            log.error("Agent run failed for trigger {}", env.eventId(), e);
        }
    }
}
