package com.shields.healthrx.generator.flow;

import java.util.Random;

import org.springframework.stereotype.Component;

import com.shields.healthrx.generator.messaging.EventTypes;

/** Chooses the next workflow event to emit for a referral, given its current status. */
@Component
public class AccessFlow {

    /** Returns the event to advance this referral one realistic step, or null if terminal. */
    public String nextEvent(String currentStatus, Random rnd) {
        return switch (currentStatus) {
            case "ELIGIBILITY_IDENTIFIED" -> EventTypes.BENEFITS_INVESTIGATION_STARTED;
            case "BENEFITS_INVESTIGATION" -> EventTypes.PRIOR_AUTHORIZATION_SUBMITTED;
            case "PRIOR_AUTH_SUBMITTED" -> rnd.nextDouble() < 0.8
                    ? EventTypes.PRIOR_AUTHORIZATION_APPROVED : EventTypes.PRIOR_AUTHORIZATION_DENIED;
            case "PRIOR_AUTH_APPROVED" -> rnd.nextDouble() < 0.5
                    ? EventTypes.FINANCIAL_ASSISTANCE_FOUND : EventTypes.READY_TO_FILL;
            case "PRIOR_AUTH_DENIED" -> EventTypes.PRIOR_AUTHORIZATION_SUBMITTED; // resubmit
            case "FINANCIAL_ASSISTANCE_REVIEW" -> EventTypes.READY_TO_FILL;
            case "READY_TO_FILL" -> EventTypes.DELIVERY_SCHEDULED;
            case "DELIVERY_SCHEDULED" -> EventTypes.THERAPY_ACTIVATED;
            default -> null;
        };
    }
}
