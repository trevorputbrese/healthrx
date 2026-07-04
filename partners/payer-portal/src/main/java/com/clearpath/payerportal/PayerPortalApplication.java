package com.clearpath.payerportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ClearPath Benefits — a fictional payer prior-authorization portal, deployed as its own CF app
 * to play the external company HealthRx's Access Workflow Agent contacts during the referral
 * lifecycle. Deliberately outside the HealthRx codebase namespace: nothing here knows about
 * HealthRx internals beyond the request payload it receives.
 */
@SpringBootApplication
public class PayerPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayerPortalApplication.class, args);
    }
}
