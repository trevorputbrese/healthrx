package com.shields.healthrx.agent.financial.partner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.shields.healthrx.agent.financial.config.AgentProperties;

/**
 * REST client for the BridgeFund Patient Assistance foundation — a genuinely external partner
 * API, so this is a plain HTTPS call (unlike every HealthRx read/write, which goes through the
 * audited MCP gateway). The distinction is deliberate and narrated in the run trace.
 *
 * <p>Tight timeouts are essential: this call runs on the agent's single event-listener thread,
 * so a hung portal must fail fast (the caller degrades gracefully) rather than freeze all agent
 * event processing.
 */
@Component
public class BridgeFundPortalClient {

    private final RestClient rest;

    public BridgeFundPortalClient(AgentProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.rest = RestClient.builder()
                .baseUrl(props.assistancePortalUrl())
                .requestFactory(factory)
                .build();
    }

    /** Submits a financial-assistance request; returns the foundation's decision document. */
    public Map<String, Object> requestDecision(String referralNumber, String medication,
            Integer copayAmount, String justification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referralNumber", referralNumber);
        body.put("medication", medication);
        body.put("copayAmount", copayAmount);
        body.put("justification", justification);
        body.put("requestedBy", "HealthRx Specialty Pharmacy — Financial Assistance Agent");
        Map<String, Object> response = rest.post()
                .uri("/api/financial-assistance/decision")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        return response == null ? Map.of() : response;
    }
}
