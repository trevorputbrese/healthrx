package com.healthrx.agent.access.partner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.healthrx.agent.access.config.AgentProperties;

/**
 * REST client for the ClearPath Benefits payer portal — a genuinely external partner API, so
 * this is a plain HTTPS call (unlike every HealthRx read/write, which goes through the audited
 * MCP gateway). The distinction is deliberate and narrated in the run trace.
 *
 * <p>Tight timeouts are essential: this call runs on the agent's single event-listener thread,
 * so a hung portal must fail fast (the caller degrades gracefully) rather than freeze all agent
 * event processing. The portal's simulated review takes up to ~1.5s; 8s covers it with margin.
 */
@Component
public class PayerPortalClient {

    private final RestClient rest;

    public PayerPortalClient(AgentProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.rest = RestClient.builder()
                .baseUrl(props.payerPortalUrl())
                .requestFactory(factory)
                .build();
    }

    /** Submits a prior-auth status request; returns the portal's decision document. */
    public Map<String, Object> requestDecision(String referralNumber, String medication, String payer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referralNumber", referralNumber);
        body.put("medication", medication);
        body.put("payer", payer);
        body.put("requestedBy", "HealthRx Specialty Pharmacy — Access Workflow Agent");
        Map<String, Object> response = rest.post()
                .uri("/api/prior-auth/decision")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        return response == null ? Map.of() : response;
    }
}
