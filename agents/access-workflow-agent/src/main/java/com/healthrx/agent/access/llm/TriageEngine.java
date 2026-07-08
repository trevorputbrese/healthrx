package com.healthrx.agent.access.llm;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import com.healthrx.agent.access.core.LlmAudit;
import com.healthrx.agent.access.core.TraceRecorder;
import com.healthrx.agent.access.core.TriageModels.Triage;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * The reasoning step: a ChatClient bound to the agent's marketplace LLM, with the Postgres MCP
 * read tools (investigate the referral) and the knowledge MCP tools (ground the summary) — every
 * call an audited gateway tool call, captured into the run's trace.
 */
@Component
public class TriageEngine {

    private static final Logger log = LoggerFactory.getLogger(TriageEngine.class);

    private static final String SYSTEM = """
            You are the Access Workflow Agent for HealthRx, a specialty pharmacy care-operations
            system. You triage specialty referrals that are new or stuck in the access pipeline
            and route ONE clear follow-up work item to the referral's owner.

            Investigate using executeQuery (read-only SQL, always add LIMIT, max 5 queries).
            Relevant tables:
              referrals(id, referral_number, patient_id, clinic_id, medication_id, payer_id,
                        owner_id, current_status, priority, received_at, pa_required,
                        pa_submitted_at, financial_assistance_required, copay_amount)
              referral_status_history(referral_id, from_status, to_status, changed_at, note)
              patients(id, first_name, last_name, disease_state)
              medications(id, name, disease_state, route, limited_distribution)
              payers(id, name, payer_type)
              clinics(id, name)
              tasks(id, referral_id, type, status, title)  -- avoid duplicating open work

            You may also call get_medication_guidance / get_condition_guidance for clinical
            context when it sharpens the summary.

            Then produce: a crisp case summary (2-3 sentences: who, what drug, where it is stuck
            and for how long), ONE concrete next action for the owner (e.g. call the payer about
            the pending PA, chase benefits verification, start financial assistance), a task
            priority (LOW/MEDIUM/HIGH/URGENT), and a short task title (<= 60 chars, imperative).

            {format}
            """;

    private final ChatClient chat;
    private final McpSyncClient postgres;
    private final McpSyncClient knowledge;

    public TriageEngine(ChatModel chatModel, McpSyncClient postgresMcp, McpSyncClient knowledgeMcp) {
        this.chat = ChatClient.builder(chatModel).build();
        this.postgres = postgresMcp;
        this.knowledge = knowledgeMcp;
    }

    public Triage triage(UUID referralId, UUID patientId, String reason, TraceRecorder trace) {
        BeanOutputConverter<Triage> converter = new BeanOutputConverter<>(Triage.class);
        List<ToolCallback> tools = recordingTools(trace);
        String user = """
                Trigger: %s
                referralId: %s
                patientId: %s
                Investigate this referral and produce the triage JSON."""
                .formatted(reason, referralId, patientId);

        String content = callModel(user, converter, tools, trace, 1);
        try {
            return converter.convert(content);
        } catch (Exception e) {
            log.warn("Triage JSON parse failed; retrying once. content={}", content);
            String retry = callModel(
                    user + "\nYour previous answer was not valid JSON. Respond with ONLY the JSON object.",
                    converter, tools, trace, 2);
            return converter.convert(retry);
        }
    }

    /** One audited model call: the llm_call log line + trace step carry model/tokens/latency. */
    private String callModel(String user, BeanOutputConverter<Triage> converter,
            List<ToolCallback> tools, TraceRecorder trace, int attempt) {
        long started = System.currentTimeMillis();
        ChatResponse response = chat.prompt()
                .system(s -> s.text(SYSTEM).param("format", converter.getFormat()))
                .user(user)
                .toolCallbacks(tools)
                .call()
                .chatResponse();
        return LlmAudit.record("access-workflow", "referral-triage", attempt, response,
                System.currentTimeMillis() - started, user, trace);
    }

    private List<ToolCallback> recordingTools(TraceRecorder trace) {
        ToolCallback[] raw = new SyncMcpToolCallbackProvider(List.of(postgres, knowledge)).getToolCallbacks();
        return Arrays.stream(raw)
                .map(tc -> (ToolCallback) new RecordingToolCallback(tc, trace))
                .toList();
    }

    private record RecordingToolCallback(ToolCallback delegate, TraceRecorder trace) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            String result = delegate.call(toolInput);
            trace.toolCall(delegate.getToolDefinition().name(), toolInput, result);
            return result;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String result = delegate.call(toolInput, toolContext);
            trace.toolCall(delegate.getToolDefinition().name(), toolInput, result);
            return result;
        }
    }
}
