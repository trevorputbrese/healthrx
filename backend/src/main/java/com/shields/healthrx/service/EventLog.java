package com.shields.healthrx.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.shields.healthrx.domain.WorkflowEventType;

/**
 * Emits a structured log line for each workflow event, named per the canonical vocabulary.
 * In Phase 2 these same call sites will publish to RabbitMQ; the centralized service methods
 * are the integration point.
 */
@Component
public class EventLog {

    private static final Logger log = LoggerFactory.getLogger("com.shields.healthrx.events");

    public void emit(WorkflowEventType type, UUID referralId, UUID patientId, String detail) {
        log.info("workflow_event type={} referralId={} patientId={} detail=\"{}\"",
                type.wireName(), referralId, patientId, detail == null ? "" : detail);
    }
}
