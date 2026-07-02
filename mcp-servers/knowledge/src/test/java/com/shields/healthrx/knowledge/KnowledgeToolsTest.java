package com.shields.healthrx.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeToolsTest {

    private final KnowledgeTools tools = new KnowledgeTools();

    @Test
    void medicationGuidanceCoversTheSeededFormularyCaseInsensitively() {
        for (String med : new String[] {"Oncora", "Velmacin", "Tarvexa", "Immunza", "Rheumavy",
                "Jakvoren", "Neurosphere", "Mylenta-S", "Releva", "Gastronib", "Colirex", "Entovia"}) {
            String json = tools.getMedicationGuidance(med);
            assertThat(json).contains("adherenceImportance").doesNotContain("\"error\"");
        }
        assertThat(tools.getMedicationGuidance("neurosphere")).contains("Multiple sclerosis");
    }

    @Test
    void conditionGuidanceCoversAllDiseaseStates() {
        for (String c : new String[] {"Oncology", "Rheumatology", "Multiple sclerosis", "Gastroenterology"}) {
            assertThat(tools.getConditionGuidance(c)).contains("outreachTips").doesNotContain("\"error\"");
        }
    }

    @Test
    void unknownInputsReturnStructuredErrors() {
        assertThat(tools.getMedicationGuidance("Aspirin")).contains("\"error\"");
        assertThat(tools.getConditionGuidance("Cardiology")).contains("\"error\"");
        assertThat(tools.getMedicationGuidance(" ")).contains("\"error\"");
    }
}
