package com.shields.healthrx.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.TaskStatus;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.repo.TaskRepository;
import com.shields.healthrx.repo.TaskRepository.TaskRow;
import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.EnumParsing;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.PageResponse;
import com.shields.healthrx.web.dto.TaskDtos;

/**
 * The Tasks page (list + status actions) plus task events: a {@code RefillDue} opens a
 * refill-follow-up task (drives medium refill risk).
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository tasks;
    private final EventLog events;
    private final AppTime time;

    public TaskService(TaskRepository tasks, EventLog events, AppTime time) {
        this.tasks = tasks;
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
     * queue (no approval chain).
     */
    @Transactional
    public TaskDtos.Item updateStatus(UUID id, String toStatusRaw) {
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
        return toItem(tasks.find(id).orElseThrow(() -> ApiException.notFound("Task", id)));
    }

    private static TaskDtos.Item toItem(TaskRow r) {
        return new TaskDtos.Item(r.id(), r.type(), r.status(), r.priority(), r.title(),
                r.description(), r.dueAt(), r.completedAt(), r.createdAt(),
                new NamedRef(r.patientId(), r.patientName()), r.referralId(), r.referralNumber(),
                new NamedRef(r.ownerId(), r.ownerName()));
    }

    @Transactional
    public void createRefillFollowUp(UUID taskId, UUID patientId, UUID referralId, UUID ownerId,
            Instant dueAt, String title) {
        tasks.insert(taskId, patientId, referralId, ownerId, "REFILL_FOLLOW_UP", "OPEN", "MEDIUM",
                title != null ? title : "Refill follow-up", null, dueAt, time.now());
        events.emit(WorkflowEventType.REFILL_DUE, referralId, patientId, "patient=" + patientId);
    }
}
