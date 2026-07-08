package com.healthrx.generator.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the shared events topic exchange so publishing succeeds regardless of startup order. */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange eventsExchange(@Value("${healthrx.events.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }
}
