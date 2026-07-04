package com.shields.healthrx.agent.access.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agent's own durable queue with narrow bindings — it senses ReferralCreated (new-referral
 * triage, phase-3-design.md §6) and PriorAuthorizationSubmitted (the payer follow-up beat);
 * stuck referrals are found by the scan, not by events.
 */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange eventsExchange(@Value("${healthrx.events.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue agentQueue(@Value("${healthrx.events.queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    Binding agentBinding(@Value("${healthrx.events.queue}") String queue,
            @Value("${healthrx.events.exchange}") String exchange,
            @Value("${healthrx.events.routing-prefix}") String prefix) {
        return BindingBuilder.bind(new Queue(queue)).to(new TopicExchange(exchange))
                .with(prefix + "ReferralCreated");
    }

    @Bean
    Binding paSubmittedBinding(@Value("${healthrx.events.queue}") String queue,
            @Value("${healthrx.events.exchange}") String exchange,
            @Value("${healthrx.events.routing-prefix}") String prefix) {
        return BindingBuilder.bind(new Queue(queue)).to(new TopicExchange(exchange))
                .with(prefix + "PriorAuthorizationSubmitted");
    }
}
