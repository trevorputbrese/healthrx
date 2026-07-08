package com.healthrx.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps the bound chat-assistant Tanzu GenAI ({@code ai-models}) service instance onto the Spring
 * AI OpenAI client — the same mapping the agent apps ship (see the agents'
 * {@code GenAiCfEnvProcessor}). Reads {@code credentials.endpoint.*} because some foundations
 * omit the top-level {@code api_base}/{@code api_key}/{@code model_name} shortcut fields; the
 * model id comes from {@code GENAI_MODEL} (application.yml default) when {@code model_name}
 * isn't in the credentials. The API binds exactly one ai-models instance (the chat assistant's),
 * so the first match wins. java-cfenv handles Postgres/RabbitMQ; this is the ai-models analogue.
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
            for (JsonNode instance : candidateInstances(services)) {
                JsonNode credentials = instance.path("credentials");
                JsonNode endpoint = credentials.path("endpoint");
                String baseUrl = endpoint.path("openai_api_base").asText(null);
                String apiKey = endpoint.path("api_key").asText(null);
                if (baseUrl == null || apiKey == null) {
                    continue;
                }
                Map<String, Object> props = new HashMap<>();
                props.put("spring.ai.openai.base-url", baseUrl);
                props.put("spring.ai.openai.api-key", apiKey);
                JsonNode modelName = credentials.path("model_name");
                if (modelName.isTextual()) {
                    props.put("spring.ai.openai.chat.options.model", modelName.asText());
                }
                environment.getPropertySources().addFirst(new MapPropertySource("genai-cfenv", props));
                return;
            }
        } catch (Exception e) {
            // Malformed VCAP_SERVICES: fall through to explicit properties/env vars.
        }
    }

    /** Only the "ai-models" offering — the API also binds postgres/rabbitmq/mcp-gateway. */
    private static List<JsonNode> candidateInstances(JsonNode services) {
        JsonNode aiModels = services.path("ai-models");
        List<JsonNode> out = new ArrayList<>();
        if (aiModels.isArray()) {
            aiModels.forEach(out::add);
        }
        return out;
    }
}
