package com.shields.healthrx.service;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.repo.FillRepository;
import com.shields.healthrx.repo.TherapyRepository;

/** Fill events: a dispense rolls the refill date; a miss leaves the therapy overdue (HIGH risk). */
@Service
public class FillService {

    private final FillRepository fills;
    private final TherapyRepository therapies;
    private final EventLog events;
    private final AppTime time;

    public FillService(FillRepository fills, TherapyRepository therapies, EventLog events, AppTime time) {
        this.fills = fills;
        this.therapies = therapies;
        this.events = events;
        this.time = time;
    }

    @Transactional
    public void record(UUID fillId, UUID patientId, UUID therapyId, UUID referralId, int daysSupply,
            LocalDate dispensedAt) {
        int number = fills.nextFillNumber(therapyId);
        LocalDate expectedRefill = dispensedAt.plusDays(daysSupply);
        fills.insert(fillId, patientId, therapyId, referralId, number, "DISPENSED", dispensedAt, daysSupply,
                expectedRefill, time.now());
        therapies.setCurrentRefillDueDate(therapyId, expectedRefill);
        events.emit(WorkflowEventType.PRESCRIPTION_FILLED, referralId, patientId,
                "therapy=" + therapyId + " due=" + expectedRefill);
    }

    @Transactional
    public void markMissed(UUID fillId, UUID patientId, UUID therapyId, UUID referralId,
            LocalDate expectedRefillDate, int daysSupply) {
        int number = fills.nextFillNumber(therapyId);
        fills.insert(fillId, patientId, therapyId, referralId, number, "MISSED", null, daysSupply,
                expectedRefillDate, time.now());
        // Roll the canonical due date to the (past) missed date so refill risk goes overdue/HIGH.
        therapies.setCurrentRefillDueDate(therapyId, expectedRefillDate);
        events.emit(WorkflowEventType.REFILL_MISSED, referralId, patientId, "therapy=" + therapyId);
    }
}
