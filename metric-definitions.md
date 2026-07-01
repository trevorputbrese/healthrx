# Phase 1 Metric Definitions

Dashboard, queue, and workbench metrics must be calculated from Postgres data. The frontend should not hard-code operational numbers.

## Application Clock

All references to `now`, `today`, and `current application time` resolve through a single injected **application clock** (`java.time.Clock` behind a `ClockProvider` bean), used identically by metric reads and by the server-side defaulting of omitted `occurredAt` / `changedAt` values.

- The clock is **pinned to a fixed demo instant** by default so that relative metrics and the seed data stay reproducible no matter when the demo is shown.
- Default anchor: `DEMO_NOW = 2026-06-29T00:00:00Z` (matches the examples in [API Contracts](api-contracts.md)).
- Override with the property `healthrx.clock.fixed-instant` (env `HEALTHRX_CLOCK_FIXED_INSTANT`). Set it to the literal `system` to use the real wall clock instead.
- All seed timestamps are authored as fixed offsets from `DEMO_NOW`, spanning at least ~210 days back, so every trend bucket and the 90-day adherence window are populated.

## Common Rules

- Default dashboard window: last 30 days ending at the current application date.
- Default trend window: last ~180 days, bucketed by calendar `month` (default) or `week`, as selected by the `bucket` query param. `from` is inclusive, `to` is exclusive. The default month window covers the six most recent whole calendar months ending at the current application month.
- Date filters use `from` inclusive and `to` exclusive.
- Filters apply to referral, patient, therapy, and fill metrics through the relevant patient, clinic, disease state, payer, medication, or owner relationships.
- Median values are the middle value after sorting. For an even count, use the average of the two middle values.
- Round day-based metrics to one decimal place in the API.
- Round percentages to whole percentages in the API.

## Referral Access Metrics

### Time To Therapy

Purpose: show how long access takes from incoming referral to active therapy.

Formula:

```text
timeToTherapyDays = active_therapy_at - received_at
```

Inclusion:

- Referral has `active_therapy_at`.
- Referral `active_therapy_at` falls inside the requested dashboard window.

Dashboard metric:

```text
medianTimeToTherapyDays = median(timeToTherapyDays)
```

Queue detail:

- For active referrals, expose completed `timeToTherapyDays`.
- For non-active referrals, expose `daysSinceReceived = now - received_at`.

### Prior Authorization Age

Purpose: show how long an in-flight PA has been waiting.

Formula:

```text
priorAuthorizationAgeDays = now - pa_submitted_at
```

Inclusion:

- Referral `current_status` is `PRIOR_AUTH_SUBMITTED`.
- `pa_submitted_at` is present.
- `pa_decided_at` is null.

### Prior Authorization Turnaround

Purpose: show elapsed time for completed PA decisions.

Formula:

```text
priorAuthorizationTurnaroundDays = pa_decided_at - pa_submitted_at
```

Inclusion:

- Referral has both `pa_submitted_at` and `pa_decided_at`.
- Current or historical decision status is `PRIOR_AUTH_APPROVED` or `PRIOR_AUTH_DENIED`.
- `pa_decided_at` falls inside the requested dashboard window.

Single-cycle semantics:

- `pa_submitted_at` / `pa_decided_at` are scalar columns reflecting the **most recent PA cycle only**. On a `PRIOR_AUTH_DENIED → PRIOR_AUTH_SUBMITTED` resubmission the service sets `pa_submitted_at = now` and clears `pa_decided_at`, so turnaround always measures the latest completed cycle. (Multi-cycle history is reconstructable from `referral_status_history` if ever needed in a later phase.)

Dashboard metric:

```text
medianPriorAuthorizationTurnaroundDays = median(priorAuthorizationTurnaroundDays)
```

### Financial Assistance Secured

Purpose: show access barrier resolution value.

Formula:

```text
financialAssistanceSecuredAmount = sum(referrals.financial_assistance_secured_amount)
financialAssistanceSecuredCount = count(referrals where amount > 0)
```

Inclusion:

- Referral has `financial_assistance_secured_amount > 0`.
- Use `ready_to_fill_at`, `active_therapy_at`, or `updated_at` as the metric date, preferring the first non-null value in that order.
- Metric date falls inside the requested dashboard window.

### Referral Status Counts

Purpose: populate queue and dashboard status breakdowns.

Formula:

```text
count by referrals.current_status
```

Inclusion:

- All non-cancelled referrals unless the caller includes `includeCancelled=true`.

## Therapy And Refill Metrics

### Active Patients On Therapy

Purpose: show current active therapy population.

Formula:

```text
activePatientsOnTherapy = count(distinct therapies.patient_id)
```

Inclusion:

- Therapy `status = ACTIVE`.
- `start_date <= today`.
- `end_date is null or end_date > today`.

### Current Refill Due Date

Purpose: give workbench and risk logic a canonical next refill date.

Formula:

```text
currentRefillDueDate = therapies.current_refill_due_date  (canonical, kept in sync with fills)
```

`therapies.current_refill_due_date` is the canonical next-refill date. It is set to the latest
dispensed fill's `dispensed_at + days_supply` when a fill is dispensed, and to the missed date when
a refill is recorded as missed (Phase 2 events) — so refill-risk logic reads one authoritative value.

Fallback:

- When the column is unset, derive it as `max(expected_refill_date for latest DISPENSED fill by therapy)`.

### PDC-Style Adherence

Purpose: provide a simple adherence indicator without trying to model all clinical edge cases.

Observation window:

```text
windowStart = max(therapy.start_date, today - 90 days)
windowEnd = today
denominatorDays = windowEnd - windowStart
```

Covered days:

- Use dispensed fills for the therapy.
- Each fill covers `dispensed_at` through `dispensed_at + days_supply`.
- Clip fill coverage to the observation window.
- Merge overlapping fill coverage so days are not double-counted.

Formula:

```text
adherencePdcPercent = min(100, round((coveredDays / denominatorDays) * 100))
```

Inclusion:

- Therapy `status = ACTIVE`.
- Denominator is at least 14 days. If less than 14 days, return `null` and display `new therapy`.

Dashboard adherence trend:

- For each bucket, compute average PDC across active therapies with at least 14 denominator days in that bucket.

### Refill Risk

Purpose: produce a transparent operational risk flag for demo workflows.

Risk is computed per active therapy.

High risk if any condition is true:

- `currentRefillDueDate < today` and there is no later dispensed fill.
- `adherencePdcPercent < 80`.
- Two or more outreach events in the last 14 days have outcome `NO_ANSWER`, `LEFT_MESSAGE`, or `NEEDS_FOLLOW_UP`, **and** the outreach condition has not been resolved (see below).

Outreach-condition resolution:

- The outreach-driven high-risk condition is considered resolved when, after the most recent qualifying unsuccessful outreach, there is either a `REACHED` outreach event or a clinical intervention of type `ADHERENCE_COUNSELING` or `CARE_COORDINATION` within the last 14 days.
- This is the demo mechanism by which a pharmacist action in Act 2 (log a successful outreach and/or an adherence intervention) visibly lowers refill risk. It does not override the refill-due or adherence conditions, which are driven by fills and time.

Medium risk if no high-risk condition is true and any condition is true:

- `currentRefillDueDate` is within the next 7 days.
- `adherencePdcPercent` is between 80 and 89 inclusive.
- There is an open `REFILL_FOLLOW_UP` or `PATIENT_CONTACT` task due within the next 3 days.

Low risk:

- No high-risk or medium-risk conditions.

API fields:

```text
refillRiskLevel: LOW | MEDIUM | HIGH
refillRiskReasons: string[]
```

Dashboard metric:

```text
refillRiskCount = count(active therapies where refillRiskLevel in (MEDIUM, HIGH))
highRefillRiskCount = count(active therapies where refillRiskLevel = HIGH)
```

## Task Metrics

### Open Tasks By Owner

Purpose: show work distribution.

Formula:

```text
count tasks where status in (OPEN, IN_PROGRESS), grouped by owner_id
```

Inclusion:

- Tasks associated with patients or referrals that match active filters.

### Overdue Tasks

Formula:

```text
overdueTaskCount = count tasks where status in (OPEN, IN_PROGRESS) and due_at < now
```

## Trend Series

The dashboard trend endpoint returns aligned time buckets. Required Phase 1 series:

- `referralsReceived`: count referrals by `received_at`.
- `activatedTherapies`: count referrals by `active_therapy_at`.
- `medianTimeToTherapyDays`: median completed time to therapy by bucket.
- `medianPriorAuthorizationTurnaroundDays`: median completed PA turnaround by bucket.
- `averageAdherencePdcPercent`: average PDC by bucket.
- `refillRiskCount`: count medium or high refill risk active therapies at bucket end.

