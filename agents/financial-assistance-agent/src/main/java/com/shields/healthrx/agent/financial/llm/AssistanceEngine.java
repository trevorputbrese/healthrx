package com.shields.healthrx.agent.financial.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import com.shields.healthrx.agent.financial.core.LlmAudit;
import com.shields.healthrx.agent.financial.core.TraceRecorder;

/**
 * The reasoning step: unlike the Access Workflow Agent's triage (which investigates and decides
 * a next action) or the Adherence Risk Agent's recommendation (which drafts a clinical plan),
 * this agent's model call does NOT decide anything — BridgeFund's answer is the actual decision.
 * The model's job is narrower: turn the case facts into a short, human-readable justification
 * to submit with the request and to narrate in the audit trail, the same way a benefits
 * coordinator would write a one-line note on a real assistance application.
 */
@Component
public class AssistanceEngine {

    private static final Logger log = LoggerFactory.getLogger(AssistanceEngine.class);

    private static final String SYSTEM = """
            You are the Financial Assistance Agent for HealthRx, a specialty pharmacy
            care-operations system. A referral's prior authorization was just approved and the
            case has been flagged as needing copay/financial assistance.

            Write ONE short, plain sentence (no more than 30 words) summarizing why this patient
            is requesting assistance, suitable to submit alongside the request to an outside
            patient-assistance foundation. State the patient's first name only, the medication,
            and the disease state. Do not mention dollar amounts or make any judgment about
            eligibility — the foundation decides that, not you. Respond with ONLY the sentence,
            no preamble, no quotes.
            """;

    private final ChatClient chat;

    public AssistanceEngine(ChatModel chatModel) {
        this.chat = ChatClient.builder(chatModel).build();
    }

    public String summarize(String patientFirstName, String medication, String diseaseState, TraceRecorder trace) {
        String user = "Patient: %s\nMedication: %s\nDisease state: %s\nWrite the one-sentence justification."
                .formatted(patientFirstName, medication, diseaseState);
        long started = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = chat.prompt().system(SYSTEM).user(user).call().chatResponse();
        } catch (Exception e) {
            log.warn("Assistance justification call failed; falling back to a plain summary", e);
            return "%s is requesting financial assistance for %s (%s).".formatted(
                    patientFirstName, medication, diseaseState);
        }
        String text = LlmAudit.record("financial-assistance", "assistance-justification", 1, response,
                System.currentTimeMillis() - started, user, trace);
        return text == null || text.isBlank()
                ? "%s is requesting financial assistance for %s (%s).".formatted(patientFirstName, medication, diseaseState)
                : text.trim();
    }
}
