package com.healthrx.agent.access.config;

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
 * Maps a bound Tanzu GenAI ({@code ai-models}) service instance onto the Spring AI OpenAI
 * client. Reads from {@code credentials.endpoint.*} — confirmed present across foundations,
 * unlike the top-level {@code api_base}/{@code api_key}/{@code wire_format}/{@code model_name}
 * shortcut fields, which some foundations omit entirely (observed on the kuhn-labs foundation
 * with a {@code tanzu-gemma-*} plan: only the nested {@code endpoint} object was present). Sets
 * {@code spring.ai.openai.base-url} / {@code api-key}; the chat model comes from the
 * {@code GENAI_MODEL} env var (application.yml default) when {@code model_name} isn't in the
 * credentials, since the model identifier must match one of the plan's advertised models
 * (visible via {@code endpoint.config_url} or {@code GET /v1/models} on the endpoint), which
 * also isn't reliably present in VCAP_SERVICES. java-cfenv handles RabbitMQ; this processor is
 * the ai-models analogue.
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

    /** Prefer the "ai-models" offering label; fall back to scanning every bound instance. */
    private static List<JsonNode> candidateInstances(JsonNode services) {
        JsonNode aiModels = services.path("ai-models");
        List<JsonNode> out = new ArrayList<>();
        if (aiModels.isArray() && !aiModels.isEmpty()) {
            aiModels.forEach(out::add);
            return out;
        }
        services.forEach(offeringInstances -> offeringInstances.forEach(out::add));
        return out;
    }
}
