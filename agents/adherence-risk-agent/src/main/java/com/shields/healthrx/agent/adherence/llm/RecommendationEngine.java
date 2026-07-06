package com.shields.healthrx.agent.adherence.llm;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

import com.shields.healthrx.agent.adherence.core.LlmAudit;
import com.shields.healthrx.agent.adherence.core.RecommendationModels.Recommendation;
import com.shields.healthrx.agent.adherence.core.TraceRecorder;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * The reasoning step (§6 run-loop steps 4–5): a ChatClient bound to the agent's marketplace LLM,
 * given the Postgres MCP read tools so the model investigates the patient itself — every query
 * is an audited gateway tool call, and each is captured into the run's trace.
 */
@Component
public class RecommendationEngine {

    private static final Logger log = LoggerFactory.getLogger(RecommendationEngine.class);

    private static final String SYSTEM = """
            You are the Adherence Risk Agent for HealthRx, a specialty pharmacy care-operations
            system. A patient just MISSED a prescription refill, which raises their refill risk to
            HIGH (conditions: refill overdue, and possibly repeated unsuccessful outreach).

            Investigate using the executeQuery tool (read-only SQL against Postgres). Relevant
            tables (always add LIMIT, max 6 queries):
              patients(id, first_name, last_name, disease_state, date_of_birth)
              therapies(id, patient_id, medication_id, status, start_date, current_refill_due_date)
              medications(id, name, disease_state)
              fills(id, therapy_id, patient_id, fill_number, status, dispensed_at, days_supply,
                    expected_refill_date)  -- status DISPENSED/MISSED
              outreach_events(id, patient_id, channel, outcome, occurred_at, notes)
                    -- outcome REACHED/LEFT_MESSAGE/NO_ANSWER/DECLINED/NEEDS_FOLLOW_UP
              clinical_interventions(id, patient_id, intervention_type, summary, occurred_at)
              referrals(id, patient_id, referral_number, current_status)

            Ground your outreach script clinically: call get_medication_guidance for the
            patient's medication (adherence importance, side effects, missed-dose guidance) and,
            when useful, get_condition_guidance for their disease state.

            Then produce your recommendation: a short risk explanation grounded in what you found,
            a patient outreach script for a phone call (warm, specific to their medication and
            missed refill), a clinical intervention proposal (type ADHERENCE_COUNSELING unless the
            data clearly suggests SIDE_EFFECT_MANAGEMENT or CARE_COORDINATION), and a refill plan
            (daysSupply, usually matching their prior fills).

            {format}
            """;

    private final ChatClient chat;
    private final McpSyncClient postgres;
    private final McpSyncClient knowledge;

    public RecommendationEngine(ChatModel chatModel, McpSyncClient postgresMcp, McpSyncClient knowledgeMcp) {
        this.chat = ChatClient.builder(chatModel).build();
        this.postgres = postgresMcp;
        this.knowledge = knowledgeMcp;
    }

    public Recommendation recommend(UUID patientId, UUID therapyId, UUID referralId, TraceRecorder trace) {
        BeanOutputConverter<Recommendation> converter = new BeanOutputConverter<>(Recommendation.class);
        List<ToolCallback> tools = recordingTools(trace);

        String user = """
                Trigger: RefillMissed
                patientId: %s
                therapyId: %s
                referralId: %s
                Investigate this patient and produce the recommendation JSON."""
                .formatted(patientId, therapyId, referralId);

        String content = callModel(user, converter, tools, trace, 1);
        try {
            return converter.convert(content);
        } catch (Exception e) {
            log.warn("Recommendation JSON parse failed; retrying once. content={}", content);
            String retry = callModel(
                    user + "\nYour previous answer was not valid JSON. Respond with ONLY the JSON object.",
                    converter, tools, trace, 2);
            return converter.convert(retry);
        }
    }

    /** One audited model call: the llm_call log line + trace step carry model/tokens/latency. */
    private String callModel(String user, BeanOutputConverter<Recommendation> converter,
            List<ToolCallback> tools, TraceRecorder trace, int attempt) {
        long started = System.currentTimeMillis();
        ChatResponse response = chat.prompt()
                .system(s -> s.text(SYSTEM).param("format", converter.getFormat()))
                .user(user)
                .toolCallbacks(tools)
                .call()
                .chatResponse();
        return LlmAudit.record("adherence-risk", "refill-missed-recommendation", attempt, response,
                System.currentTimeMillis() - started, user, trace);
    }

    /** The Postgres + knowledge MCP tools, wrapped so every model call lands in the trace. */
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
