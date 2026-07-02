package com.shields.healthrx.knowledge;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Curated reference guidance for the seeded HealthRx formulary — a static dataset, not RAG
 * (phase-3-design.md §5.3). All medications and guidance are FICTIONAL demo content authored for
 * the seeded specialty-pharmacy world; the agents use it to ground outreach scripts and case
 * summaries.
 */
@Component
public class KnowledgeTools {

    private record MedInfo(String diseaseState, String route, String adherenceImportance,
            String commonSideEffects, String missedDoseGuidance, String monitoring) {
    }

    private static final Map<String, MedInfo> MEDICATIONS = Map.ofEntries(
            Map.entry("oncora", new MedInfo("Oncology", "Oral",
                    "Continuous daily dosing sustains tumor suppression; unplanned gaps of more than 3 days can reduce response and should be flagged to the prescriber.",
                    "Fatigue, nausea, hand-foot skin reaction, mild hypertension.",
                    "Take the next scheduled dose — do not double up. A gap over 3 days warrants a prescriber check-in before resuming.",
                    "Blood pressure weekly for the first cycle; CBC and liver panel every 4 weeks.")),
            Map.entry("velmacin", new MedInfo("Oncology", "Infusion",
                    "Infusion cycles are scheduled to maintain therapeutic troughs; missed cycles delay the whole regimen.",
                    "Infusion reactions, cytopenias, fatigue.",
                    "Reschedule the infusion as soon as possible; the care team should re-verify labs if more than 7 days late.",
                    "CBC before each cycle; infusion-reaction watch for 1 hour post-dose.")),
            Map.entry("tarvexa", new MedInfo("Oncology", "Injectable",
                    "Self-injection on schedule keeps drug levels within the therapeutic window.",
                    "Injection-site reactions, low-grade fever, myalgia.",
                    "Inject when remembered unless within 48 hours of the next dose; never double doses.",
                    "Injection technique review at refill; periodic CBC.")),
            Map.entry("immunza", new MedInfo("Rheumatology", "Subcutaneous",
                    "Consistent weekly dosing prevents flare rebound; adherence gaps commonly precede disease flares.",
                    "Injection-site reactions, headache, mild immunosuppression (infection risk).",
                    "Take the missed dose as soon as remembered, then resume the weekly schedule anchored to the new day.",
                    "Annual TB screen; report persistent fever or infection promptly.")),
            Map.entry("rheumavy", new MedInfo("Rheumatology", "Injectable",
                    "Biologic dosing rhythm maintains remission; stopping abruptly risks flare and anti-drug antibodies.",
                    "Injection-site reactions, upper respiratory infections.",
                    "Inject when remembered; if more than a week late, confirm with the care team before resuming.",
                    "Symptom check each refill; periodic inflammatory markers.")),
            Map.entry("jakvoren", new MedInfo("Rheumatology", "Oral",
                    "Short half-life makes daily adherence critical — symptom return within days of missed doses is common.",
                    "Headache, nausea, elevated cholesterol, infection risk.",
                    "Take the next dose at the usual time; do not double up.",
                    "Lipid panel at 12 weeks; CBC periodically.")),
            Map.entry("neurosphere", new MedInfo("Multiple sclerosis", "Oral",
                    "Daily dosing reduces relapse frequency; adherence gaps are associated with breakthrough MS activity within weeks.",
                    "Flushing, GI upset (usually improves after the first month), lymphopenia.",
                    "Take the next scheduled dose — do not double. Gaps over 7 days may require re-titration; involve the prescriber.",
                    "CBC with lymphocyte count every 3 months.")),
            Map.entry("mylenta-s", new MedInfo("Multiple sclerosis", "Infusion",
                    "Scheduled infusions maintain immunomodulation; late infusions extend relapse-risk windows.",
                    "Infusion reactions, headache, infection risk.",
                    "Reschedule promptly; the neurology team should be informed of delays beyond 2 weeks.",
                    "Pre-infusion labs; JCV antibody status per protocol.")),
            Map.entry("releva", new MedInfo("Multiple sclerosis", "Injectable",
                    "Regular self-injection sustains relapse protection; missed weeks reduce efficacy.",
                    "Injection-site reactions, flu-like symptoms for 24-48h post-dose.",
                    "Inject when remembered unless the next dose is within 2 days; keep the usual weekly anchor.",
                    "Rotation of injection sites; liver panel every 6 months.")),
            Map.entry("gastronib", new MedInfo("Gastroenterology", "Subcutaneous",
                    "Maintenance dosing keeps IBD in remission; missed doses commonly precede symptom recurrence and loss of response.",
                    "Injection-site reactions, headache, infection risk.",
                    "Take the missed dose as soon as remembered, then resume the original schedule.",
                    "Symptom diary at refills; periodic fecal calprotectin per GI team.")),
            Map.entry("colirex", new MedInfo("Gastroenterology", "Infusion",
                    "Infusion intervals are optimized to maintain trough levels; extended gaps risk anti-drug antibodies and loss of response.",
                    "Infusion reactions, headache, fatigue.",
                    "Reschedule as soon as possible; troughs may need re-checking after a long gap.",
                    "Pre-infusion vitals; drug-level/antibody monitoring per protocol.")),
            Map.entry("entovia", new MedInfo("Gastroenterology", "Oral",
                    "Daily dosing controls mucosal inflammation; adherence gaps show up as symptom flare within 1-2 weeks.",
                    "Nausea, headache, mild lymphopenia.",
                    "Take the next dose at the usual time; do not double up.",
                    "CBC periodically; report persistent GI bleeding immediately.")));

    private static final Map<String, String[]> CONDITIONS = Map.of(
            "oncology", new String[] {
                    "Oral oncolytic adherence directly affects response; patients often under-report skipped doses due to side-effect fear. Cost and copay burden is the most common root cause of missed oncology refills.",
                    "Lead with side-effect check-in before adherence questions; mention financial assistance options explicitly; involve the prescriber early for tolerability-driven gaps." },
            "rheumatology", new String[] {
                    "Patients in remission frequently self-discontinue believing they are cured; flares then follow within weeks. Injection fatigue is common after 6+ months.",
                    "Reinforce that remission is maintained BY the medication; ask about injection-site pain and offer technique coaching; schedule refills around symptom patterns." },
            "multiple sclerosis", new String[] {
                    "Relapse risk rises within weeks of missed doses even when the patient feels well; cognitive/fatigue symptoms can themselves cause unintentional non-adherence.",
                    "Use reminder systems and caregiver loops where welcomed; emphasize protection against invisible disease activity; keep outreach short and concrete." },
            "gastroenterology", new String[] {
                    "IBD patients often stop therapy when symptoms quiet down; loss of response after gaps can be permanent for biologics (anti-drug antibodies).",
                    "Explain the loss-of-response risk plainly; tie refill timing to symptom-free streaks as a win; flag any flare signs to the GI team." });

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "get_medication_guidance", description = """
            Curated guidance for a HealthRx formulary medication: why adherence matters, common
            side effects, missed-dose guidance, and monitoring notes. Returns JSON.""")
    public String getMedicationGuidance(
            @ToolParam(description = "Medication name, e.g. Neurosphere") String medicationName) {
        if (medicationName == null || medicationName.isBlank()) {
            return error("medicationName is required");
        }
        MedInfo info = MEDICATIONS.get(medicationName.trim().toLowerCase(Locale.ROOT));
        if (info == null) {
            return error("Unknown medication: " + medicationName + ". Known: "
                    + String.join(", ", MEDICATIONS.keySet()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("medication", medicationName.trim());
        out.put("diseaseState", info.diseaseState());
        out.put("route", info.route());
        out.put("adherenceImportance", info.adherenceImportance());
        out.put("commonSideEffects", info.commonSideEffects());
        out.put("missedDoseGuidance", info.missedDoseGuidance());
        out.put("monitoring", info.monitoring());
        return json(out);
    }

    @Tool(name = "get_condition_guidance", description = """
            Curated adherence/outreach context for a disease state (Oncology, Rheumatology,
            Multiple sclerosis, Gastroenterology). Returns JSON.""")
    public String getConditionGuidance(
            @ToolParam(description = "Disease state, e.g. Multiple sclerosis") String diseaseState) {
        if (diseaseState == null || diseaseState.isBlank()) {
            return error("diseaseState is required");
        }
        String[] info = CONDITIONS.get(diseaseState.trim().toLowerCase(Locale.ROOT));
        if (info == null) {
            return error("Unknown disease state: " + diseaseState + ". Known: "
                    + String.join(", ", CONDITIONS.keySet()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diseaseState", diseaseState.trim());
        out.put("adherenceContext", info[0]);
        out.put("outreachTips", info[1]);
        return json(out);
    }

    private String error(String message) {
        return json(Map.of("error", message));
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
