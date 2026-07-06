package com.shields.healthrx.agent.financial.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates the run's trace — what the agent saw, queried, concluded, and did — persisted in
 * the AgentRecommendationCreated payload and rendered by the Agents view.
 */
public class TraceRecorder {

    private final List<Map<String, Object>> steps = new ArrayList<>();

    public synchronized void step(String type, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", type);
        step.put("detail", detail);
        steps.add(step);
    }

    public synchronized void toolCall(String tool, String input, String resultPreview) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "query");
        step.put("tool", tool);
        step.put("input", truncate(input, 800));
        step.put("result", truncate(resultPreview, 800));
        steps.add(step);
    }

    /** The model call itself: identity, token usage, latency, and the raw completion. */
    public synchronized void llmCall(String model, Integer promptTokens, Integer completionTokens,
            long latencyMs, int attempt, String prompt, String completion) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "reasoning");
        step.put("tool", "llm.chat_completion");
        StringBuilder detail = new StringBuilder("model ").append(model);
        if (promptTokens != null || completionTokens != null) {
            detail.append(" · ").append(promptTokens == null ? "?" : promptTokens)
                    .append(" tokens in / ")
                    .append(completionTokens == null ? "?" : completionTokens).append(" out");
        }
        detail.append(" · ").append(String.format(java.util.Locale.US, "%.1f", latencyMs / 1000.0))
                .append('s');
        if (attempt > 1) {
            detail.append(" · attempt ").append(attempt);
        }
        step.put("detail", detail.toString());
        step.put("input", truncate(prompt, 800));
        step.put("result", truncate(completion, 800));
        steps.add(step);
    }

    public synchronized List<Map<String, Object>> steps() {
        return List.copyOf(steps);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
