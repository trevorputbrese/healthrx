package com.healthrx.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.healthrx.service.TaskService;
import com.healthrx.web.dto.PageResponse;
import com.healthrx.web.dto.TaskDtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    /** status accepts a TaskStatus name or the pseudo-filter OPEN_ALL (OPEN + IN_PROGRESS). */
    @GetMapping
    public PageResponse<TaskDtos.Item> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(status, search, page, size);
    }

    public record StatusChange(@NotBlank String toStatus) {
    }

    /** Completing a task may also advance its linked referral — the result says when it did. */
    @PatchMapping("/{id}/status")
    public TaskDtos.StatusChangeResult updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusChange body) {
        return service.updateStatus(id, body.toStatus());
    }
}
