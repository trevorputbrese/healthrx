package com.healthrx.domain;

/**
 * Canonical workflow event vocabulary shared by Phase 1 logging / status history and Phase 2
 * event ingestion. The {@link #wireName()} is the PascalCase value stored in
 * {@code referral_status_history.phase2_event_type} and used in structured log lines.
 *
 * <p>See data-model.md (Phase 2 Event Alignment) — this enum is the single source of truth.
 */
public enum WorkflowEventType {
    REFERRAL_CREATED("ReferralCreated"),
    BENEFITS_INVESTIGATION_STARTED("BenefitsInvestigationStarted"),
    PRIOR_AUTHORIZATION_SUBMITTED("PriorAuthorizationSubmitted"),
    PRIOR_AUTHORIZATION_APPROVED("PriorAuthorizationApproved"),
    PRIOR_AUTHORIZATION_DENIED("PriorAuthorizationDenied"),
    FINANCIAL_ASSISTANCE_FOUND("FinancialAssistanceFound"),
    READY_TO_FILL("ReadyToFill"),
    DELIVERY_SCHEDULED("DeliveryScheduled"),
    THERAPY_ACTIVATED("TherapyActivated"),
    REFERRAL_CANCELLED("ReferralCancelled"),
    // Reserved for Phase 2 (fills and tasks are read-only seed data in Phase 1).
    PRESCRIPTION_FILLED("PrescriptionFilled"),
    REFILL_DUE("RefillDue"),
    REFILL_MISSED("RefillMissed"),
    // Emitted as structured log lines from their service methods.
    PATIENT_OUTREACH_LOGGED("PatientOutreachLogged"),
    CLINICAL_INTERVENTION_CREATED("ClinicalInterventionCreated"),
    // Reserved for Phase 3: agents emit these when they produce / a human applies a recommendation.
    AGENT_RECOMMENDATION_CREATED("AgentRecommendationCreated"),
    AGENT_RECOMMENDATION_APPLIED("AgentRecommendationApplied");

    private final String wireName;

    WorkflowEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static java.util.Optional<WorkflowEventType> fromWire(String wire) {
        if (wire == null) {
            return java.util.Optional.empty();
        }
        for (WorkflowEventType v : values()) {
            if (v.wireName.equals(wire)) {
                return java.util.Optional.of(v);
            }
        }
        return java.util.Optional.empty();
    }
}
