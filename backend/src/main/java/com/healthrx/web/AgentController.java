package com.healthrx.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.healthrx.service.AgentOpsService;
import com.healthrx.web.dto.AgentDtos;
import com.healthrx.web.dto.PageResponse;

/** The Agents view API. See phase-3-design.md §8 and api-contracts.md (Agents). */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentOpsService service;

    public AgentController(AgentOpsService service) {
        this.service = service;
    }

    @GetMapping
    public AgentDtos.AgentsResponse agents() {
        return service.agents();
    }

    @GetMapping("/recommendations")
    public PageResponse<AgentDtos.Recommendation> recommendations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.feed(status, agent, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @PostMapping("/recommendations/{id}/approve")
    public AgentDtos.Recommendation approve(@PathVariable UUID id, @RequestBody AgentDtos.Decision body) {
        return service.approve(id, required(body));
    }

    @PostMapping("/recommendations/{id}/dismiss")
    public AgentDtos.Recommendation dismiss(@PathVariable UUID id, @RequestBody AgentDtos.Decision body) {
        return service.dismiss(id, required(body));
    }

    @PostMapping("/{name}/pause")
    public Map<String, Object> pause(@PathVariable String name) {
        service.setPaused(name, true);
        return Map.of("agent", name, "paused", true);
    }

    @PostMapping("/{name}/resume")
    public Map<String, Object> resume(@PathVariable String name) {
        service.setPaused(name, false);
        return Map.of("agent", name, "paused", false);
    }

    private static UUID required(AgentDtos.Decision body) {
        if (body == null || body.decidedById() == null) {
            throw ApiException.badRequest("MISSING_FIELD", "decidedById is required.",
                    Map.of("field", "decidedById"));
        }
        return body.decidedById();
    }
}
