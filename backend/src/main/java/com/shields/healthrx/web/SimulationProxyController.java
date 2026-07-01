package com.shields.healthrx.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Proxies the Simulation control panel to the generator app, so the SPA talks to a single origin
 * (the API) rather than the generator directly. Returns the generator's JSON, or 502 when the
 * generator is unreachable.
 */
@RestController
@RequestMapping("/api/simulation")
public class SimulationProxyController {

    private static final Logger log = LoggerFactory.getLogger(SimulationProxyController.class);

    private final RestClient client;

    public SimulationProxyController(@Value("${healthrx.generator.url}") String generatorBaseUrl) {
        this.client = RestClient.builder().baseUrl(generatorBaseUrl).build();
    }

    @GetMapping("/status")
    public ResponseEntity<String> status() {
        return forward(HttpMethod.GET, "/sim/status");
    }

    @PostMapping("/start")
    public ResponseEntity<String> start() {
        return forward(HttpMethod.POST, "/sim/start");
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stop() {
        return forward(HttpMethod.POST, "/sim/stop");
    }

    @PostMapping("/speed")
    public ResponseEntity<String> speed(@RequestParam int value) {
        return forward(HttpMethod.POST, "/sim/speed?value=" + value);
    }

    @PostMapping("/scenario/{name}")
    public ResponseEntity<String> scenario(@PathVariable String name) {
        return forward(HttpMethod.POST, "/sim/scenario/" + name);
    }

    private ResponseEntity<String> forward(HttpMethod method, String path) {
        try {
            String body = client.method(method).uri(path).retrieve().body(String.class);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (Exception e) {
            log.warn("Generator proxy failed: {} {}", method, path, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":\"GENERATOR_UNAVAILABLE\",\"message\":\"Generator is not reachable.\"}");
        }
    }
}
