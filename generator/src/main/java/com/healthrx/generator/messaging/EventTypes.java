package com.healthrx.generator.messaging;

/** Workflow event wire names (PascalCase), mirroring the API's WorkflowEventType vocabulary. */
public final class EventTypes {

    private EventTypes() {
    }

    public static final String REFERRAL_CREATED = "ReferralCreated";
    public static final String BENEFITS_INVESTIGATION_STARTED = "BenefitsInvestigationStarted";
    public static final String PRIOR_AUTHORIZATION_SUBMITTED = "PriorAuthorizationSubmitted";
    public static final String PRIOR_AUTHORIZATION_APPROVED = "PriorAuthorizationApproved";
    public static final String PRIOR_AUTHORIZATION_DENIED = "PriorAuthorizationDenied";
    public static final String FINANCIAL_ASSISTANCE_FOUND = "FinancialAssistanceFound";
    public static final String READY_TO_FILL = "ReadyToFill";
    public static final String DELIVERY_SCHEDULED = "DeliveryScheduled";
    public static final String THERAPY_ACTIVATED = "TherapyActivated";
    public static final String REFERRAL_CANCELLED = "ReferralCancelled";
    public static final String PRESCRIPTION_FILLED = "PrescriptionFilled";
    public static final String REFILL_DUE = "RefillDue";
    public static final String REFILL_MISSED = "RefillMissed";
    public static final String PATIENT_OUTREACH_LOGGED = "PatientOutreachLogged";
    public static final String CLINICAL_INTERVENTION_CREATED = "ClinicalInterventionCreated";
}
