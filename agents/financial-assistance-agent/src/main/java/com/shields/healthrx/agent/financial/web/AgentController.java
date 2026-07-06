package com.shields.healthrx.agent.financial.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shields.healthrx.agent.financial.config.AgentProperties;

/**
 * The agent's small control API (status + pause/resume nudge). This agent is autonomous — its
 * recommendations are born AUTO_APPLIED, so there is no apply endpoint; approvals never route
 * here (the API's gate rejects non-PENDING rows with 409).
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final AgentProperties props;

    public AgentController(AgentProperties props) {
        this.props = props;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("agent", props.name(), "up", true);
    }

    @PostMapping("/control/refresh")
    public Map<String, Object> refresh() {
        // Pause state is read per trigger from agent_control (durable); nothing cached.
        return Map.of("agent", props.name(), "refreshed", true);
    }
}
