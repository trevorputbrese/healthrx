package com.healthrx.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HealthRx synthetic data generator. Advances a shared simulated clock and publishes a realistic
 * ambient stream of workflow events to RabbitMQ (consumed by the HealthRx API). Also exposes a
 * small control API for presenter-triggered scenarios.
 */
@SpringBootApplication
@EnableScheduling
public class GeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeneratorApplication.class, args);
    }
}
