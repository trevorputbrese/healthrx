package com.shields.healthrx.domain;

import java.util.UUID;

/** Fixed care-team-member ids for non-human actors (seeded in V3). */
public final class SystemActors {

    private SystemActors() {
    }

    /** The synthetic-data generator / event-applied writes. */
    public static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Phase 3 AI agents. */
    public static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
}
