package com.healthrx.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.healthrx.config.AppTime;
import com.healthrx.domain.ReferralStatus;
import com.healthrx.domain.TaskStatus;
import com.healthrx.domain.TaskType;
import com.healthrx.domain.WorkflowEventType;
import com.healthrx.repo.TaskRepository;
import com.healthrx.repo.TaskRepository.TaskRow;
import com.healthrx.web.ApiException;
import com.healthrx.web.EnumParsing;
import com.healthrx.web.dto.CommonDtos.NamedRef;
import com.healthrx.web.dto.PageResponse;
import com.healthrx.web.dto.TaskDtos;

/**
 * The Tasks page (list + status actions) plus task events: a {@code RefillDue} opens a
 * refill-follow-up task (drives medium refill risk).
 *
 * <p>Completing an agent-routed access task DOES the work it asked for: the linked referral is
 * advanced to the stage's next status (see {@link #NEXT_STATUS_ON_COMPLETE}), which re-broadcasts
 * onto the event bus and hands the case back to the agents. The reverse link lives in
 * {@link ReferralService#transition}: advancing the referral auto-completes its open tasks.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /**
     * Where completing an access follow-up task sends its referral. Each entry reads "the task
     * asked for this stage's human work; done means the referral moves on": intake review starts
     * the benefits investigation (the agent takes it from there), an appeal or a benefits task
     * resubmits/submits the prior auth, and a financial-assistance review releases the fill.
     */
    private static final Map<ReferralStatus, ReferralStatus> NEXT_STATUS_ON_COMPLETE = Map.of(
            ReferralStatus.ELIGIBILITY_IDENTIFIED, ReferralStatus.BENEFITS_INVESTIGATION,
            ReferralStatus.BENEFITS_INVESTIGATION, ReferralStatus.PRIOR_AUTH_SUBMITTED,
            ReferralStatus.PRIOR_AUTH_DENIED, ReferralStatus.PRIOR_AUTH_SUBMITTED,
            ReferralStatus.FINANCIAL_ASSISTANCE_REVIEW, ReferralStatus.READY_TO_FILL);

    private final TaskRepository tasks;
    private final ReferralService referralService;
    private final EventLog events;
    private final AppTime time;

    public TaskService(TaskRepository tasks, ReferralService referralService, EventLog events, AppTime time) {
        this.tasks = tasks;
        this.referralService = referralService;
        this.events = events;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDtos.Item> list(String status, String search, int page, int size) {
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        int normalizedPage = Math.max(page, 0);
        if (status != null && !status.isBlank() && !"OPEN_ALL".equals(status)) {
            EnumParsing.require(TaskStatus.class, status, "status");
        }
        List<TaskDtos.Item> items = tasks.page(status, search, normalizedPage, normalizedSize)
                .stream().map(TaskService::toItem).toList();
        return PageResponse.of(items, normalizedPage, normalizedSize, tasks.count(status, search));
    }

    /**
     * Human status actions from the Tasks page: complete, cancel, start, or reopen. Terminal
     * statuses can only be reopened — everything else is a free transition for a demo work
     * queue (no approval chain). Completing an access follow-up also advances the linked
     * referral to its mapped next status (the completion IS the work).
     */
    @Transactional
    public TaskDtos.StatusChangeResult updateStatus(UUID id, String toStatusRaw) {
        TaskRow row = tasks.find(id).orElseThrow(() -> ApiException.notFound("Task", id));
        TaskStatus from = TaskStatus.valueOf(row.status());
        TaskStatus to = EnumParsing.require(TaskStatus.class, toStatusRaw, "toStatus");
        boolean terminal = from == TaskStatus.COMPLETED || from == TaskStatus.CANCELLED;
        if (from == to || (terminal && to != TaskStatus.OPEN)) {
            throw ApiException.conflict("INVALID_TASK_TRANSITION",
                    "Task is " + from.label() + "; cannot move to " + to.label() + ".",
                    Map.of("from", from.name(), "to", to.name()));
        }
        Instant now = time.now();
        tasks.updateStatus(id, to.name(), to == TaskStatus.COMPLETED ? now : null);
        log.info("task_status_changed task={} from={} to={}", id, from, to);

        TaskDtos.ReferralAdvance advance =
                to == TaskStatus.COMPLETED ? advanceLinkedReferral(row) : null;
        TaskDtos.Item item = toItem(tasks.find(id).orElseThrow(() -> ApiException.notFound("Task", id)));
        return new TaskDtos.StatusChangeResult(item, advance);
    }

    /**
     * The task-completion side of the task↔referral link: the referral moves to the mapped next
     * status as the task's owner. The transition re-validates the edge, so a lost race with a
     * concurrent advance surfaces as INVALID_TRANSITION rather than a silent double-move.
     */
    private TaskDtos.ReferralAdvance advanceLinkedReferral(TaskRow row) {
        ReferralStatus target = nextStatusFor(row);
        if (target == null) {
            return null;
        }
        referralService.transition(row.referralId(), target.name(), row.ownerId(),
                "Completed task: " + row.title());
        log.info("task_completion_advanced_referral task={} referral={} to={}",
                row.id(), row.referralId(), target);
        return new TaskDtos.ReferralAdvance(row.referralId(), row.referralNumber(),
                target.name(), target.label());
    }

    /** The advance completing this task would perform right now, or null when there is none. */
    private static ReferralStatus nextStatusFor(TaskRow row) {
        if (row.referralId() == null || !TaskType.ACCESS_FOLLOW_UP.name().equals(row.type())) {
            return null;
        }
        ReferralStatus current = ReferralStatus.tryParse(row.referralStatus()).orElse(null);
        ReferralStatus target = current == null ? null : NEXT_STATUS_ON_COMPLETE.get(current);
        return target != null && current.canTransitionTo(target) ? target : null;
    }

    private static TaskDtos.Item toItem(TaskRow r) {
        boolean active = TaskStatus.OPEN.name().equals(r.status())
                || TaskStatus.IN_PROGRESS.name().equals(r.status());
        ReferralStatus next = active ? nextStatusFor(r) : null;
        TaskDtos.ReferralAdvance advancesTo = next == null ? null
                : new TaskDtos.ReferralAdvance(r.referralId(), r.referralNumber(), next.name(), next.label());
        return new TaskDtos.Item(r.id(), r.type(), r.status(), r.priority(), r.title(),
                r.description(), r.dueAt(), r.completedAt(), r.createdAt(),
                new NamedRef(r.patientId(), r.patientName()), r.referralId(), r.referralNumber(),
                new NamedRef(r.ownerId(), r.ownerName()), advancesTo);
    }

    @Transactional
    public void createRefillFollowUp(UUID taskId, UUID patientId, UUID referralId, UUID ownerId,
            Instant dueAt, String title) {
        tasks.insert(taskId, patientId, referralId, ownerId, "REFILL_FOLLOW_UP", "OPEN", "MEDIUM",
                title != null ? title : "Refill follow-up", null, dueAt, time.now());
        events.emit(WorkflowEventType.REFILL_DUE, referralId, patientId, "patient=" + patientId);
    }
}
