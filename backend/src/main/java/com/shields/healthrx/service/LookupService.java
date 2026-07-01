package com.shields.healthrx.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.domain.InterventionType;
import com.shields.healthrx.domain.LabeledEnum;
import com.shields.healthrx.domain.OutreachChannel;
import com.shields.healthrx.domain.OutreachOutcome;
import com.shields.healthrx.domain.Priority;
import com.shields.healthrx.domain.ReferralStatus;
import com.shields.healthrx.domain.TaskType;
import com.shields.healthrx.repo.LookupRepository;
import com.shields.healthrx.web.dto.LookupDtos;
import com.shields.healthrx.web.dto.LookupDtos.Option;
import com.shields.healthrx.web.dto.LookupDtos.StatusOption;

/** Assembles lookup values for filters and form dropdowns. */
@Service
public class LookupService {

    private final LookupRepository lookups;

    public LookupService(LookupRepository lookups) {
        this.lookups = lookups;
    }

    @Transactional(readOnly = true)
    public LookupDtos.Lookups all() {
        List<StatusOption> statuses = Arrays.stream(ReferralStatus.values())
                .map(s -> new StatusOption(s.name(), s.label(),
                        s.allowedNextStatuses().stream().map(Enum::name).toList()))
                .toList();
        List<String> priorities = Arrays.stream(Priority.values()).map(Enum::name).toList();

        return new LookupDtos.Lookups(
                statuses,
                priorities,
                lookups.clinics(),
                lookups.payers(),
                lookups.medications(),
                lookups.owners(),
                lookups.diseaseStates(),
                options(TaskType.values()),
                options(OutreachChannel.values()),
                options(OutreachOutcome.values()),
                options(InterventionType.values()));
    }

    private static List<Option> options(LabeledEnum[] values) {
        return Arrays.stream(values).map(v -> new Option(v.name(), v.label())).toList();
    }
}
