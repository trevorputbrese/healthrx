package com.shields.healthrx.agent.access.config;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps a bound Tanzu GenAI ({@code ai-models}) service instance onto the Spring AI OpenAI
 * client: the binding's credentials are OpenAI wire format (spike §11.1 — {@code api_base} +
 * JWT {@code api_key} + {@code model_name}), so this sets {@code spring.ai.openai.base-url},
 * {@code api-key}, and the chat model. java-cfenv handles RabbitMQ; this processor is the
 * ai-models analogue.
 */
public class GenAiCfEnvProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String vcap = environment.getProperty("VCAP_SERVICES");
        if (vcap == null || vcap.isBlank()) {
            return;
        }
        try {
            JsonNode services = new ObjectMapper().readTree(vcap);
            Iterator<Map.Entry<String, JsonNode>> fields = services.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                for (JsonNode instance : entry.getValue()) {
                    JsonNode creds = instance.path("credentials");
                    if (!"openai".equals(creds.path("wire_format").asText())
                            || creds.path("api_base").isMissingNode()) {
                        continue;
                    }
                    Map<String, Object> props = new HashMap<>();
                    props.put("spring.ai.openai.base-url", creds.path("api_base").asText());
                    props.put("spring.ai.openai.api-key", creds.path("api_key").asText());
                    if (creds.hasNonNull("model_name")) {
                        props.put("spring.ai.openai.chat.options.model", creds.path("model_name").asText());
                    }
                    environment.getPropertySources().addFirst(new MapPropertySource("genai-cfenv", props));
                    return;
                }
            }
        } catch (Exception e) {
            // Malformed VCAP_SERVICES: fall through to explicit properties/env vars.
        }
    }
}
