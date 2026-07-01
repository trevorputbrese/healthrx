package com.shields.healthrx.domain;

/** Patient-journey timeline item categories. See api-contracts.md (GET /patients/{id}/timeline). */
public enum TimelineType {
    REFERRAL,
    STATUS_CHANGE,
    FILL,
    TASK,
    OUTREACH,
    INTERVENTION,
    NOTE,
    FINANCIAL,
    AGENT
}
