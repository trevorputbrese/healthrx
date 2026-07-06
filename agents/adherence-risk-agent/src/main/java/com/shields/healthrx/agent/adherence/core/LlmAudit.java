package com.shields.healthrx.agent.adherence.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Audit trail for every model call: one structured {@code llm_call} log line (grep-able,
 * drain-friendly — the app-layer complement to the gateway's tool-call audit, which never sees
 * LLM traffic because agents call the model endpoint directly), plus a trace step so the
 * reasoning is persisted with the run and visible in the Agents feed.
 *
 * <p>Model identity and token usage are read from the RESPONSE metadata, not from config, so
 * swapping the bound ai-models instance changes what gets audited with no code change.
 */
public final class LlmAudit {

    private static final Logger log = LoggerFactory.getLogger("com.shields.healthrx.agent.llm.audit");

    private LlmAudit() {
    }

    /** Records the call in the audit log and the run trace; returns the completion text. */
    public static String record(String agent, String purpose, int attempt, ChatResponse response,
            long latencyMs, String userPrompt, TraceRecorder trace) {
        String completion = "";
        String model = "unknown";
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;

        if (response != null) {
            if (response.getResult() != null && response.getResult().getOutput() != null
                    && response.getResult().getOutput().getText() != null) {
                completion = response.getResult().getOutput().getText();
            }
            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata != null) {
                if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                    model = metadata.getModel();
                }
                Usage usage = metadata.getUsage();
                if (usage != null) {
                    promptTokens = usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens();
                    totalTokens = usage.getTotalTokens();
                }
            }
        }

        log.info("llm_call agent={} purpose={} attempt={} model={} latency_ms={} prompt_tokens={} "
                + "completion_tokens={} total_tokens={} user_prompt=\"{}\" completion=\"{}\"",
                agent, purpose, attempt, model, latencyMs, promptTokens, completionTokens, totalTokens,
                oneLine(userPrompt, 500), oneLine(completion, 4000));
        trace.llmCall(model, promptTokens, completionTokens, latencyMs, attempt, userPrompt, completion);
        return completion;
    }

    private static String oneLine(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
