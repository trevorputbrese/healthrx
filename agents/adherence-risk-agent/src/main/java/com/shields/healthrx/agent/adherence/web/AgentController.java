package com.shields.healthrx.agent.adherence.web;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shields.healthrx.agent.adherence.config.AgentProperties;
import com.shields.healthrx.agent.adherence.core.ApplyService;

/**
 * The agent's small control API, called only by the HealthRx API's proxy (phase-3-design.md §6/§8):
 * apply an approved recommendation, report status, and accept the pause/resume nudge (pause state
 * itself is durable in agent_control; the nudge is best-effort).
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ApplyService applyService;
    private final AgentProperties props;

    public AgentController(ApplyService applyService, AgentProperties props) {
        this.applyService = applyService;
        this.props = props;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("agent", props.name(), "up", true);
    }

    @PostMapping("/control/refresh")
    public Map<String, Object> refresh() {
        // Pause state is read per trigger from agent_control (durable); nothing to cache-bust yet.
        return Map.of("agent", props.name(), "refreshed", true);
    }

    @PostMapping("/recommendations/{id}/apply")
    public ResponseEntity<Map<String, Object>> apply(@PathVariable UUID id) {
        try {
            applyService.apply(id);
            return ResponseEntity.ok(Map.of("recommendationId", id.toString(), "applied", true));
        } catch (Exception e) {
            log.error("Apply failed for {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("recommendationId", id.toString(), "applied", false,
                            "error", String.valueOf(e.getMessage())));
        }
    }
}
