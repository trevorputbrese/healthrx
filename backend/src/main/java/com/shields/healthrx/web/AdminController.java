package com.shields.healthrx.web;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shields.healthrx.service.ResetService;

/** Demo administration. Unauthenticated by design (Phase 1/2 demo); the UI guards with a confirm. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ResetService resetService;
    private final JdbcTemplate jdbc;

    public AdminController(ResetService resetService, JdbcTemplate jdbc) {
        this.resetService = resetService;
        this.jdbc = jdbc;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        resetService.resetDemo();
        Long referrals = jdbc.queryForObject("select count(*) from referrals", Long.class);
        return Map.of("status", "reset", "seededReferrals", referrals == null ? 0 : referrals);
    }
}
