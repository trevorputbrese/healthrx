package com.shields.healthrx.generator.flow;

import org.springframework.stereotype.Component;

import com.shields.healthrx.generator.messaging.EventTypes;

/**
 * Chooses the next workflow event to emit for a referral, given its current status.
 *
 * <p>Deliberately has NO cases for {@code PRIOR_AUTH_SUBMITTED} or {@code PRIOR_AUTH_APPROVED} —
 * those are real-world decisions made by an outside party (the payer, a patient-assistance
 * foundation), so this ambient world-mover never picks a referral in either status (see
 * {@code WorldReader.pickAdvanceableReferral}) and never guesses their outcome. The only ways
 * out of those two statuses are the Access Workflow Agent (contacting ClearPath Benefits) and
 * the Financial Assistance Agent (contacting BridgeFund) — or, if an agent is paused, the
 * referral simply waits, same as a real case with nobody following up.
 */
@Component
public class AccessFlow {

    /** Returns the event to advance this referral one realistic step, or null if terminal. */
    public String nextEvent(String currentStatus) {
        return switch (currentStatus) {
            case "ELIGIBILITY_IDENTIFIED" -> EventTypes.BENEFITS_INVESTIGATION_STARTED;
            case "BENEFITS_INVESTIGATION" -> EventTypes.PRIOR_AUTHORIZATION_SUBMITTED;
            case "PRIOR_AUTH_DENIED" -> EventTypes.PRIOR_AUTHORIZATION_SUBMITTED; // resubmit
            case "FINANCIAL_ASSISTANCE_REVIEW" -> EventTypes.READY_TO_FILL;
            case "READY_TO_FILL" -> EventTypes.DELIVERY_SCHEDULED;
            case "DELIVERY_SCHEDULED" -> EventTypes.THERAPY_ACTIVATED;
            default -> null;
        };
    }
}
