package com.bridgefund.assistanceportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BridgeFund Patient Assistance — a fictional independent copay/patient-assistance foundation,
 * deployed as its own CF app to play the external company HealthRx's Financial Assistance Agent
 * contacts during the referral lifecycle. Deliberately outside the HealthRx codebase namespace:
 * nothing here knows about HealthRx internals beyond the request payload it receives.
 */
@SpringBootApplication
public class BridgeFundPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(BridgeFundPortalApplication.class, args);
    }
}
