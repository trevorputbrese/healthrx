package com.healthrx.agent.adherence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdherenceAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdherenceAgentApplication.class, args);
    }
}
