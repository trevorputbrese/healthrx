package com.healthrx.agent.adherence.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The structured recommendation the LLM must produce (phase-3-design.md §6 run-loop step 5). */
public final class RecommendationModels {

    private RecommendationModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recommendation(
            String summary,
            String riskExplanation,
            Outreach outreach,
            Intervention intervention,
            RefillPlan refillPlan) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Outreach(String channel, String script) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intervention(String type, String rationale) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefillPlan(Integer daysSupply, String note) {
    }
}
