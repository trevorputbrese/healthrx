package com.shields.healthrx.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the event topology: a topic exchange the API binds with {@code healthrx.event.#},
 * plus a fanout dead-letter exchange/queue for unprocessable or non-applicable events. These
 * beans are auto-declared against the broker on startup when one is available.
 */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange eventsExchange(@Value("${healthrx.events.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    FanoutExchange deadLetterExchange(@Value("${healthrx.events.dlx}") String dlx) {
        return new FanoutExchange(dlx, true, false);
    }

    @Bean
    Queue apiQueue(@Value("${healthrx.events.queue}") String queue, @Value("${healthrx.events.dlx}") String dlx) {
        return QueueBuilder.durable(queue).withArgument("x-dead-letter-exchange", dlx).build();
    }

    @Bean
    Queue deadLetterQueue(@Value("${healthrx.events.dlq}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    Binding apiBinding(@Value("${healthrx.events.queue}") String queue,
            @Value("${healthrx.events.exchange}") String exchange,
            @Value("${healthrx.events.routing-prefix}") String prefix) {
        return BindingBuilder.bind(new Queue(queue)).to(new TopicExchange(exchange)).with(prefix + "#");
    }

    @Bean
    Binding deadLetterBinding(@Value("${healthrx.events.dlq}") String dlq, @Value("${healthrx.events.dlx}") String dlx) {
        return BindingBuilder.bind(new Queue(dlq)).to(new FanoutExchange(dlx));
    }
}
