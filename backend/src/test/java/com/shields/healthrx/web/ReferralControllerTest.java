package com.shields.healthrx.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.shields.healthrx.service.ReferralService;
import com.shields.healthrx.web.dto.CommonDtos.EntityRef;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.CommonDtos.PatientRef;
import com.shields.healthrx.web.dto.PageResponse;
import com.shields.healthrx.web.dto.ReferralDtos;

@WebMvcTest(ReferralController.class)
class ReferralControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReferralService service;

    private ReferralDtos.Summary sampleSummary() {
        UUID id = UUID.randomUUID();
        return new ReferralDtos.Summary(id, "RX-10042",
                new PatientRef(UUID.randomUUID(), "Jordan Ellis", "Oncology"),
                new EntityRef(UUID.randomUUID(), "Northside Oncology"),
                new EntityRef(UUID.randomUUID(), "Oncora"),
                new EntityRef(UUID.randomUUID(), "Atlas Commercial"),
                new NamedRef(UUID.randomUUID(), "Trevor Putbrese"),
                "PRIOR_AUTH_SUBMITTED", "HIGH", Instant.parse("2026-06-20T13:00:00Z"),
                9.1, 4.5, null, new BigDecimal("1200.00"), BigDecimal.ZERO, 2, null);
    }

    @Test
    void queueReturnsPagedEnvelope() throws Exception {
        when(service.queue(any())).thenReturn(PageResponse.of(List.of(sampleSummary()), 0, 25, 1));
        mvc.perform(get("/api/referrals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].referralNumber").value("RX-10042"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].priorAuthorizationAgeDays").value(4.5));
    }

    @Test
    void detailNotFoundMapsTo404Json() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.detail(eq(id))).thenThrow(ApiException.notFound("Referral", id));
        mvc.perform(get("/api/referrals/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidTransitionMapsTo409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.transition(any(), any(), any(), any()))
                .thenThrow(ApiException.invalidTransition("READY_TO_FILL", "PRIOR_AUTH_SUBMITTED"));
        mvc.perform(patch("/api/referrals/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"PRIOR_AUTH_SUBMITTED\",\"changedById\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.details.fromStatus").value("READY_TO_FILL"));
    }

    @Test
    void missingActorIsUnprocessable() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(patch("/api/referrals/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"PRIOR_AUTH_SUBMITTED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
