package com.shields.healthrx.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.domain.SystemActors;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.repo.ReferralRepository;
import com.shields.healthrx.repo.ReferralRepository.StatusHistoryInsert;

/** Creates a new referral from a {@code ReferralCreated} event (no Phase 1 create path existed). */
@Service
public class ReferralIntakeService {

    private final ReferralRepository referrals;
    private final EventLog events;

    public ReferralIntakeService(ReferralRepository referrals, EventLog events) {
        this.referrals = referrals;
        this.events = events;
    }

    @Transactional
    public String create(UUID referralId, UUID patientId, UUID clinicId, UUID medicationId, UUID payerId,
            UUID ownerId, String priority, boolean paRequired, boolean financialAssistanceRequired,
            Instant receivedAt) {
        String number = referrals.nextReferralNumber();
        referrals.insertReferral(referralId, number, patientId, clinicId, medicationId, payerId, ownerId,
                priority, receivedAt, paRequired, financialAssistanceRequired, receivedAt);
        referrals.insertStatusHistory(new StatusHistoryInsert(UUID.randomUUID(), referralId, null,
                "ELIGIBILITY_IDENTIFIED", receivedAt, SystemActors.SYSTEM, null,
                WorkflowEventType.REFERRAL_CREATED.wireName()));
        events.emit(WorkflowEventType.REFERRAL_CREATED, referralId, patientId, "number=" + number);
        return number;
    }
}
