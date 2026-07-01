package com.shields.healthrx.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.repo.TaskRepository;

/** Task events: a {@code RefillDue} opens a refill-follow-up task (drives medium refill risk). */
@Service
public class TaskService {

    private final TaskRepository tasks;
    private final EventLog events;
    private final AppTime time;

    public TaskService(TaskRepository tasks, EventLog events, AppTime time) {
        this.tasks = tasks;
        this.events = events;
        this.time = time;
    }

    @Transactional
    public void createRefillFollowUp(UUID taskId, UUID patientId, UUID referralId, UUID ownerId,
            Instant dueAt, String title) {
        tasks.insert(taskId, patientId, referralId, ownerId, "REFILL_FOLLOW_UP", "OPEN", "MEDIUM",
                title != null ? title : "Refill follow-up", null, dueAt, time.now());
        events.emit(WorkflowEventType.REFILL_DUE, referralId, patientId, "patient=" + patientId);
    }
}
