package com.shields.healthrx.web;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.repo.DashboardFilter;
import com.shields.healthrx.service.DashboardService;
import com.shields.healthrx.web.dto.DashboardDtos;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;
    private final AppTime time;

    public DashboardController(DashboardService service, AppTime time) {
        this.service = service;
        this.time = time;
    }

    @GetMapping("/summary")
    public DashboardDtos.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID clinicId,
            @RequestParam(required = false) String diseaseState,
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) UUID medicationId,
            @RequestParam(required = false) UUID ownerId) {
        LocalDate end = to != null ? to : time.today();
        LocalDate start = from != null ? from : end.minusDays(30);
        return service.summary(filter(start, end, clinicId, diseaseState, payerId, medicationId, ownerId));
    }

    @GetMapping("/trends")
    public DashboardDtos.Trends trends(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "month") String bucket,
            @RequestParam(required = false) UUID clinicId,
            @RequestParam(required = false) String diseaseState,
            @RequestParam(required = false) UUID payerId,
            @RequestParam(required = false) UUID medicationId,
            @RequestParam(required = false) UUID ownerId) {
        LocalDate end = to != null ? to : time.today();
        LocalDate start = from != null ? from : end.minusDays(180);
        return service.trends(filter(start, end, clinicId, diseaseState, payerId, medicationId, ownerId), bucket);
    }

    private DashboardFilter filter(LocalDate from, LocalDate to, UUID clinicId, String diseaseState,
            UUID payerId, UUID medicationId, UUID ownerId) {
        // diseaseState is free text sourced from /api/lookups; just normalize blanks to null.
        String disease = diseaseState == null || diseaseState.isBlank() ? null : diseaseState.trim();
        return new DashboardFilter(from, to, clinicId, disease, payerId, medicationId, ownerId);
    }
}
