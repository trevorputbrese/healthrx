package com.shields.healthrx.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shields.healthrx.domain.InterventionType;
import com.shields.healthrx.domain.OutreachChannel;
import com.shields.healthrx.domain.OutreachOutcome;
import com.shields.healthrx.domain.TimelineType;
import com.shields.healthrx.service.PatientService;
import com.shields.healthrx.web.dto.PageResponse;
import com.shields.healthrx.web.dto.PatientDtos;
import com.shields.healthrx.web.dto.TimelineDtos;
import com.shields.healthrx.web.request.Requests;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PatientDtos.Summary> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String diseaseState,
            @RequestParam(defaultValue = "name,asc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(search, diseaseState, sort, page, size);
    }

    @GetMapping("/{id}")
    public PatientDtos.Detail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/timeline")
    public TimelineDtos.Response timeline(
            @PathVariable UUID id,
            @RequestParam(required = false) List<String> type,
            @RequestParam(defaultValue = "50") int limit) {
        Set<String> types = type == null ? Set.of()
                : type.stream().map(t -> EnumParsing.require(TimelineType.class, t, "type").name())
                        .collect(java.util.stream.Collectors.toSet());
        return service.timeline(id, types, limit);
    }

    @PostMapping("/{id}/outreach")
    @ResponseStatus(HttpStatus.CREATED)
    public PatientDtos.OutreachResult outreach(@PathVariable UUID id, @Valid @RequestBody Requests.Outreach body) {
        OutreachChannel channel = EnumParsing.require(OutreachChannel.class, body.channel(), "channel");
        OutreachOutcome outcome = EnumParsing.require(OutreachOutcome.class, body.outcome(), "outcome");
        return service.logOutreach(id, body.referralId(), body.ownerId(), channel, outcome,
                body.occurredAt(), body.notes());
    }

    @PostMapping("/{id}/interventions")
    @ResponseStatus(HttpStatus.CREATED)
    public PatientDtos.InterventionResult intervention(@PathVariable UUID id,
            @Valid @RequestBody Requests.Intervention body) {
        InterventionType type = EnumParsing.require(InterventionType.class, body.interventionType(), "interventionType");
        return service.logIntervention(id, body.referralId(), body.ownerId(), type, body.summary(),
                body.occurredAt());
    }
}
