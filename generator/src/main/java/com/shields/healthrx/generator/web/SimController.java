package com.shields.healthrx.generator.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shields.healthrx.generator.scenario.ScenarioService;
import com.shields.healthrx.generator.sim.SimulationClock;
import com.shields.healthrx.generator.sim.SimulationStateRepository.State;

/** Control surface for the generator: start/stop the ambient stream, set speed, run scenarios. */
@RestController
@RequestMapping("/sim")
public class SimController {

    private static final List<String> SCENARIOS =
            List.of("new-referral", "advance-referral", "submit-prior-auth", "send-at-risk", "resolve-risk");

    private final SimulationClock clock;
    private final ScenarioService scenarios;

    public SimController(SimulationClock clock, ScenarioService scenarios) {
        this.clock = clock;
        this.scenarios = scenarios;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        State s = clock.state();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("currentInstant", s.currentInstant().toString());
        m.put("speedSecondsPerSecond", s.speedSecondsPerSecond());
        m.put("ambientEnabled", s.ambientEnabled());
        m.put("scenarios", SCENARIOS);
        return m;
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        clock.start();
        return status();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        clock.stop();
        return status();
    }

    @PostMapping("/speed")
    public Map<String, Object> speed(@RequestParam int value) {
        clock.setSpeed(value);
        return status();
    }

    /** Toggles the ambient trickle independently of Start/Pause — time can still advance quietly. */
    @PostMapping("/ambient")
    public Map<String, Object> ambient(@RequestParam boolean enabled) {
        clock.setAmbientEnabled(enabled);
        return status();
    }

    @PostMapping("/scenario/{name}")
    public Map<String, Object> scenario(@PathVariable String name) {
        return scenarios.run(name);
    }
}
