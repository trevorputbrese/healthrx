package com.healthrx.agent.access.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrx.agent.access.core.AccessAgentService;
import com.healthrx.agent.access.core.BenefitsCheckService;
import com.healthrx.agent.access.core.PayerCheckService;

/**
 * Senses the agent's triggers off its narrow-bound durable queue and dispatches by event type:
 * ReferralCreated -> LLM triage; BenefitsInvestigationStarted -> deterministic benefits check +
 * PA submission; PriorAuthorizationSubmitted -> deterministic payer follow-up.
 */
@Component
@ConditionalOnProperty(name = "healthrx.events.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ReferralCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ReferralCreatedListener.class);

    private final AccessAgentService agent;
    private final BenefitsCheckService benefitsCheck;
    private final PayerCheckService payerCheck;
    private final ObjectMapper mapper;

    public ReferralCreatedListener(AccessAgentService agent, BenefitsCheckService benefitsCheck,
            PayerCheckService payerCheck, ObjectMapper mapper) {
        this.agent = agent;
        this.benefitsCheck = benefitsCheck;
        this.payerCheck = payerCheck;
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
            switch (env.eventType() == null ? "" : env.eventType()) {
                case "ReferralCreated" -> agent.onReferralCreated(env);
                case "BenefitsInvestigationStarted" -> benefitsCheck.onBenefitsInvestigationStarted(env);
                case "PriorAuthorizationSubmitted" -> payerCheck.onPriorAuthSubmitted(env);
                default -> log.debug("Ignoring event type {}", env.eventType());
            }
        } catch (Exception e) {
            // Deterministic ids + the emit-repair guard make a lost run safe; log, don't requeue-loop.
            log.error("Agent run failed for trigger {}", env.eventId(), e);
        }
    }
}
