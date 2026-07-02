package com.shields.healthrx.agent.access.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The structured triage output the LLM must produce (phase-3-design.md §6 Access run-loop). */
public final class TriageModels {

    private TriageModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Triage(
            String summary,
            String nextAction,
            String priority,
            String taskTitle) {
    }
}
