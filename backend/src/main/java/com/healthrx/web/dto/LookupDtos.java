package com.healthrx.web.dto;

import java.util.List;

import com.healthrx.web.dto.CommonDtos.EntityRef;
import com.healthrx.web.dto.CommonDtos.NamedRef;

/** Lookup values for filters and forms. See api-contracts.md (GET /api/lookups). */
public final class LookupDtos {

    private LookupDtos() {
    }

    public record Option(String value, String label) {
    }

    public record StatusOption(String value, String label, List<String> nextStatuses) {
    }

    public record Lookups(
            List<StatusOption> referralStatuses,
            List<String> priorities,
            List<EntityRef> clinics,
            List<EntityRef> payers,
            List<EntityRef> medications,
            List<NamedRef> owners,
            List<String> diseaseStates,
            List<Option> taskTypes,
            List<Option> outreachChannels,
            List<Option> outreachOutcomes,
            List<Option> interventionTypes) {
    }
}
