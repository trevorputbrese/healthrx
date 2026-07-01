package com.shields.healthrx.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shields.healthrx.domain.Priority;
import com.shields.healthrx.domain.ReferralStatus;
import com.shields.healthrx.repo.QueueFilter;
import com.shields.healthrx.service.ReferralService;
import com.shields.healthrx.web.dto.PageResponse;
import com.shields.healthrx.web.dto.ReferralDtos;
import com.shields.healthrx.web.request.Requests;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/referrals")
public class ReferralController {

    private final ReferralService service;

    public ReferralController(ReferralService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ReferralDtos.Summary> queue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID clinicId,
            @RequestParam(required = false) String diseaseState,
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) UUID medicationId,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "false") boolean includeCancelled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {

        String statusFilter = EnumParsing.optional(ReferralStatus.class, status, "status");
        String priorityFilter = EnumParsing.optional(Priority.class, priority, "priority");

        String sortField = "receivedAt";
        String sortDir = "desc";
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            sortField = parts[0].trim();
            if (parts.length > 1) {
                sortDir = parts[1].trim();
            }
        }

        QueueFilter filter = new QueueFilter(statusFilter, clinicId,
                blankToNull(diseaseState), payerId, medicationId, ownerId, priorityFilter,
                blankToNull(search), includeCancelled, page, size, sortField, sortDir);
        return service.queue(filter);
    }

    @GetMapping("/{id}")
    public ReferralDtos.Detail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PatchMapping("/{id}/status")
    public ReferralDtos.TransitionResult transition(@PathVariable UUID id,
            @Valid @RequestBody Requests.StatusTransition body) {
        return service.transition(id, body.toStatus(), body.changedById(), body.note());
    }

    @PatchMapping("/{id}/financials")
    public ReferralDtos.FinancialsResult financials(@PathVariable UUID id,
            @Valid @RequestBody Requests.Financials body) {
        return service.updateFinancials(id, body.copayAmount(), body.financialAssistanceSecuredAmount(),
                body.financialAssistanceRequired(), body.changedById(), body.note());
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferralDtos.NoteResult addNote(@PathVariable UUID id, @Valid @RequestBody Requests.Note body) {
        return service.addNote(id, body.authorId(), body.body());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
