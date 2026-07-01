# Phase 1 API Contracts

All endpoints are rooted at `/api`. Request and response payloads use JSON. Field names use lower camel case. IDs are UUID strings.

## Frontend Views And Routing

The three product views map to client routes as follows:

| Route | View | Primary endpoints |
| --- | --- | --- |
| `/queue` | Enrollment & Access Queue | `GET /api/referrals`, `GET /api/lookups` |
| `/referrals/:referralId` | Referral access detail (milestones, status advance, financials, notes, history) | `GET /api/referrals/{id}`, `PATCH .../status`, `PATCH .../financials`, `POST .../notes` |
| `/patients/:patientId` | Patient Therapy Workbench (therapies, adherence, refill risk, timeline, outreach + interventions) | `GET /api/patients/{id}`, `GET .../timeline`, `POST .../outreach`, `POST .../interventions` |
| `/dashboard` | Outcomes Dashboard | `GET /api/dashboard/summary`, `GET /api/dashboard/trends` |

A queue row opens the referral access detail; the access detail links to the patient workbench via `patient.id`. `GET /api/referrals/{id}` is the access view (not a duplicate of the patient workbench). `refillRiskLevel` on a referral reflects the risk of its linked active therapy, or `null` when there is none. Timeline `actor` is nullable (e.g. `FILL` events and status rows with no `changed_by_id`).

## Common API Rules

### Dates And Times

- Date-only values use ISO `YYYY-MM-DD`.
- Timestamps use ISO-8601 with offset, for example `2026-06-29T14:30:00Z`.
- When clients omit `occurredAt` or `changedAt`, the server uses the current application time.

### Actor Identity

Authentication is deferred in Phase 1. Mutating endpoints require an explicit actor id (`changedById`, `authorId`, or `ownerId`) referencing an active `care_team_members` row. The frontend supplies this from a single persistent **"Acting as" selector** in the app header (populated from `GET /api/lookups` owners). A `404` is returned when the actor id does not resolve to a care team member.

### Pagination

List endpoints use:

```text
page: zero-based page number, default 0
size: page size, default 25, max 100
sort: field,direction, for example receivedAt,desc
```

Paginated responses:

```json
{
  "items": [],
  "page": 0,
  "size": 25,
  "totalItems": 0,
  "totalPages": 0
}
```

### Error Model

```json
{
  "code": "INVALID_STATUS_TRANSITION",
  "message": "Cannot move referral from READY_TO_FILL to PRIOR_AUTH_SUBMITTED.",
  "details": {
    "fromStatus": "READY_TO_FILL",
    "toStatus": "PRIOR_AUTH_SUBMITTED"
  }
}
```

Status codes:

| Status | Use |
| --- | --- |
| `200` | Successful read or update |
| `201` | Successful create |
| `400` | Malformed request, invalid enum, invalid filter |
| `404` | Resource not found |
| `409` | Valid request shape but invalid workflow transition or conflicting state |
| `422` | Semantically valid JSON with missing required domain data |
| `500` | Unexpected server error |

## Shared DTOs

### Referral Summary

```json
{
  "id": "uuid",
  "referralNumber": "RX-10042",
  "patient": {
    "id": "uuid",
    "displayName": "Jordan Ellis",
    "diseaseState": "Oncology"
  },
  "clinic": {
    "id": "uuid",
    "name": "Northside Oncology"
  },
  "medication": {
    "id": "uuid",
    "name": "Oncora"
  },
  "payer": {
    "id": "uuid",
    "name": "Atlas Commercial"
  },
  "owner": {
    "id": "uuid",
    "displayName": "Maya Patel"
  },
  "currentStatus": "PRIOR_AUTH_SUBMITTED",
  "priority": "HIGH",
  "receivedAt": "2026-06-20T13:00:00Z",
  "daysSinceReceived": 9.1,
  "priorAuthorizationAgeDays": 4.5,
  "timeToTherapyDays": null,
  "copayAmount": 1200.00,
  "financialAssistanceSecuredAmount": 0.00,
  "openTaskCount": 2,
  "refillRiskLevel": null
}
```

### Timeline Item

```json
{
  "id": "uuid",
  "type": "STATUS_CHANGE",
  "occurredAt": "2026-06-20T13:00:00Z",
  "title": "Prior authorization submitted",
  "body": "Submitted to Atlas Commercial.",
  "actor": {
    "id": "uuid",
    "displayName": "Maya Patel"
  },
  "metadata": {
    "fromStatus": "BENEFITS_INVESTIGATION",
    "toStatus": "PRIOR_AUTH_SUBMITTED"
  }
}
```

## Endpoints

### `GET /api/referrals`

Returns referral queue rows.

Query params:

```text
status
clinicId
diseaseState
payerId
medicationId
ownerId
priority
search
includeCancelled
page
size
sort
```

Response `200`:

```json
{
  "items": [
    {
      "id": "uuid",
      "referralNumber": "RX-10042",
      "patient": {
        "id": "uuid",
        "displayName": "Jordan Ellis",
        "diseaseState": "Oncology"
      },
      "clinic": {
        "id": "uuid",
        "name": "Northside Oncology"
      },
      "medication": {
        "id": "uuid",
        "name": "Oncora"
      },
      "payer": {
        "id": "uuid",
        "name": "Atlas Commercial"
      },
      "owner": {
        "id": "uuid",
        "displayName": "Maya Patel"
      },
      "currentStatus": "PRIOR_AUTH_SUBMITTED",
      "priority": "HIGH",
      "receivedAt": "2026-06-20T13:00:00Z",
      "daysSinceReceived": 9.1,
      "priorAuthorizationAgeDays": 4.5,
      "timeToTherapyDays": null,
      "copayAmount": 1200.00,
      "financialAssistanceSecuredAmount": 0.00,
      "openTaskCount": 2,
      "refillRiskLevel": null
    }
  ],
  "page": 0,
  "size": 25,
  "totalItems": 1,
  "totalPages": 1
}
```

### `GET /api/referrals/{id}`

Returns one referral with related workbench context.

Response `200`:

```json
{
  "id": "uuid",
  "referralNumber": "RX-10042",
  "patient": {
    "id": "uuid",
    "displayName": "Jordan Ellis",
    "dateOfBirth": "1978-04-12",
    "diseaseState": "Oncology"
  },
  "clinic": {
    "id": "uuid",
    "name": "Northside Oncology"
  },
  "medication": {
    "id": "uuid",
    "name": "Oncora",
    "route": "Oral"
  },
  "payer": {
    "id": "uuid",
    "name": "Atlas Commercial",
    "payerType": "Commercial"
  },
  "owner": {
    "id": "uuid",
    "displayName": "Maya Patel"
  },
  "currentStatus": "PRIOR_AUTH_SUBMITTED",
  "allowedNextStatuses": ["PRIOR_AUTH_APPROVED", "PRIOR_AUTH_DENIED", "CANCELLED"],
  "priority": "HIGH",
  "receivedAt": "2026-06-20T13:00:00Z",
  "milestones": {
    "benefitsInvestigationStartedAt": "2026-06-21T15:00:00Z",
    "paSubmittedAt": "2026-06-24T16:00:00Z",
    "paDecidedAt": null,
    "readyToFillAt": null,
    "deliveryScheduledAt": null,
    "activeTherapyAt": null
  },
  "financials": {
    "copayAmount": 1200.00,
    "financialAssistanceRequired": true,
    "financialAssistanceSecuredAmount": 0.00
  },
  "metrics": {
    "daysSinceReceived": 9.1,
    "priorAuthorizationAgeDays": 4.5,
    "timeToTherapyDays": null
  },
  "openTasks": [],
  "recentNotes": [],
  "statusHistory": []
}
```

### `PATCH /api/referrals/{id}/status`

Advances or cancels a referral status. The backend validates the transition graph.

Request:

```json
{
  "toStatus": "PRIOR_AUTH_APPROVED",
  "changedById": "uuid",
  "note": "PA approved through 2027-06-29."
}
```

Response `200`:

```json
{
  "id": "uuid",
  "currentStatus": "PRIOR_AUTH_APPROVED",
  "allowedNextStatuses": ["FINANCIAL_ASSISTANCE_REVIEW", "READY_TO_FILL", "CANCELLED"],
  "statusHistoryItem": {
    "id": "uuid",
    "fromStatus": "PRIOR_AUTH_SUBMITTED",
    "toStatus": "PRIOR_AUTH_APPROVED",
    "changedAt": "2026-06-29T14:30:00Z",
    "changedBy": {
      "id": "uuid",
      "displayName": "Maya Patel"
    },
    "note": "PA approved through 2027-06-29."
  }
}
```

Errors:

- `400` for unknown `toStatus`.
- `404` for missing referral or actor.
- `409` for invalid transition.

### `PATCH /api/referrals/{id}/financials`

Records financial-assistance and copay outcomes for a referral. Used during the access workflow (typically around `FINANCIAL_ASSISTANCE_REVIEW`). Setting `financialAssistanceSecuredAmount > 0` emits a `FinancialAssistanceFound` event and adds a `FINANCIAL` timeline entry.

Request (all financial fields optional; at least one required):

```json
{
  "changedById": "uuid",
  "copayAmount": 1200.00,
  "financialAssistanceSecuredAmount": 5000.00,
  "financialAssistanceRequired": true,
  "note": "Secured manufacturer copay assistance."
}
```

Response `200`:

```json
{
  "id": "uuid",
  "financials": {
    "copayAmount": 1200.00,
    "financialAssistanceRequired": true,
    "financialAssistanceSecuredAmount": 5000.00
  },
  "updatedAt": "2026-06-29T14:30:00Z"
}
```

Errors:

- `400` for negative amounts or an empty body.
- `404` for missing referral or actor.

### `POST /api/referrals/{id}/notes`

Adds a referral note.

Request:

```json
{
  "authorId": "uuid",
  "body": "Patient prefers afternoon calls."
}
```

Response `201`:

```json
{
  "id": "uuid",
  "referralId": "uuid",
  "author": {
    "id": "uuid",
    "displayName": "Maya Patel"
  },
  "body": "Patient prefers afternoon calls.",
  "createdAt": "2026-06-29T14:30:00Z"
}
```

### `GET /api/patients/{id}`

Returns patient profile, therapy summary, and operational context.

Response `200`:

```json
{
  "id": "uuid",
  "demoMrn": "PX-2042",
  "displayName": "Jordan Ellis",
  "dateOfBirth": "1978-04-12",
  "diseaseState": "Oncology",
  "clinic": {
    "id": "uuid",
    "name": "Northside Oncology"
  },
  "payer": {
    "id": "uuid",
    "name": "Atlas Commercial"
  },
  "primaryOwner": {
    "id": "uuid",
    "displayName": "Maya Patel"
  },
  "therapies": [
    {
      "id": "uuid",
      "medication": {
        "id": "uuid",
        "name": "Oncora"
      },
      "status": "ACTIVE",
      "startDate": "2026-05-01",
      "currentRefillDueDate": "2026-07-05",
      "adherencePdcPercent": 86,
      "refillRiskLevel": "MEDIUM",
      "refillRiskReasons": ["Refill due within 7 days"]
    }
  ],
  "openTasks": [],
  "recentOutreach": [],
  "recentInterventions": []
}
```

### `GET /api/patients/{id}/timeline`

Returns a combined patient journey timeline.

Query params:

```text
from
to
type
limit, default 50, max 200
```

`type` may be repeated. Supported values:

```text
REFERRAL
STATUS_CHANGE
FILL
TASK
OUTREACH
INTERVENTION
NOTE
FINANCIAL
```

Response `200`:

```json
{
  "items": [
    {
      "id": "uuid",
      "type": "OUTREACH",
      "occurredAt": "2026-06-29T14:30:00Z",
      "title": "Phone outreach logged",
      "body": "Left message about upcoming refill.",
      "actor": {
        "id": "uuid",
        "displayName": "Maya Patel"
      },
      "metadata": {
        "channel": "PHONE",
        "outcome": "LEFT_MESSAGE"
      }
    }
  ]
}
```

### `POST /api/patients/{id}/outreach`

Logs a patient outreach event.

Request:

```json
{
  "referralId": "uuid",
  "ownerId": "uuid",
  "channel": "PHONE",
  "outcome": "LEFT_MESSAGE",
  "occurredAt": "2026-06-29T14:30:00Z",
  "notes": "Left refill reminder voicemail."
}
```

Response `201`:

```json
{
  "id": "uuid",
  "patientId": "uuid",
  "referralId": "uuid",
  "owner": {
    "id": "uuid",
    "displayName": "Maya Patel"
  },
  "channel": "PHONE",
  "outcome": "LEFT_MESSAGE",
  "occurredAt": "2026-06-29T14:30:00Z",
  "notes": "Left refill reminder voicemail."
}
```

### `POST /api/patients/{id}/interventions`

Logs a clinical intervention.

Request:

```json
{
  "referralId": "uuid",
  "ownerId": "uuid",
  "interventionType": "ADHERENCE_COUNSELING",
  "summary": "Reviewed refill schedule and side effect mitigation plan.",
  "occurredAt": "2026-06-29T14:30:00Z"
}
```

Response `201`:

```json
{
  "id": "uuid",
  "patientId": "uuid",
  "referralId": "uuid",
  "owner": {
    "id": "uuid",
    "displayName": "Maya Patel"
  },
  "interventionType": "ADHERENCE_COUNSELING",
  "summary": "Reviewed refill schedule and side effect mitigation plan.",
  "occurredAt": "2026-06-29T14:30:00Z"
}
```

### `GET /api/dashboard/summary`

Returns current dashboard tiles and breakdowns.

Query params:

```text
from
to
clinicId
diseaseState
payerId
medicationId
ownerId
```

Response `200`:

```json
{
  "window": {
    "from": "2026-05-30",
    "to": "2026-06-29"
  },
  "tiles": {
    "activePatientsOnTherapy": 86,
    "medianTimeToTherapyDays": 8.2,
    "medianPriorAuthorizationTurnaroundDays": 3.4,
    "refillRiskCount": 14,
    "highRefillRiskCount": 5,
    "averageAdherencePdcPercent": 87,
    "financialAssistanceSecuredAmount": 48250.00,
    "financialAssistanceSecuredCount": 18,
    "overdueTaskCount": 9
  },
  "statusCounts": [
    {
      "status": "PRIOR_AUTH_SUBMITTED",
      "count": 11
    }
  ],
  "openTasksByOwner": [
    {
      "owner": {
        "id": "uuid",
        "displayName": "Maya Patel"
      },
      "count": 7
    }
  ]
}
```

### `GET /api/dashboard/trends`

Returns aligned trend series for charts.

Query params:

```text
from
to
bucket=week|month
clinicId
diseaseState
payerId
medicationId
ownerId
```

Response `200`:

```json
{
  "bucket": "month",
  "series": [
    {
      "from": "2026-01-01",
      "to": "2026-02-01",
      "referralsReceived": 26,
      "activatedTherapies": 18,
      "medianTimeToTherapyDays": 8.7,
      "medianPriorAuthorizationTurnaroundDays": 3.1,
      "averageAdherencePdcPercent": 88,
      "refillRiskCount": 12
    }
  ]
}
```

### `GET /api/lookups`

Returns lookup values required by filters and forms.

Response `200`:

```json
{
  "referralStatuses": [
    {
      "value": "PRIOR_AUTH_SUBMITTED",
      "label": "Prior authorization submitted",
      "nextStatuses": ["PRIOR_AUTH_APPROVED", "PRIOR_AUTH_DENIED", "CANCELLED"]
    }
  ],
  "priorities": ["LOW", "MEDIUM", "HIGH", "URGENT"],
  "clinics": [],
  "payers": [],
  "medications": [],
  "owners": [],
  "diseaseStates": ["Oncology", "Rheumatology", "Multiple sclerosis", "Gastroenterology"],
  "taskTypes": [],
  "outreachChannels": [],
  "outreachOutcomes": [],
  "interventionTypes": []
}
```

## Simulation Control (Phase 2)

The API proxies a small control surface to the synthetic data generator app, so the SPA's
Simulation panel talks to one origin. The generator advances the shared simulated clock and
publishes the ambient event stream consumed by the API.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/simulation/status` | `{ enabled, currentInstant, speedSecondsPerSecond, scenarios }` |
| `POST /api/simulation/start` | Start the ambient stream (the generator begins advancing the clock). |
| `POST /api/simulation/stop` | Pause the stream. The simulated clock **freezes** at its current instant (it does not revert). |
| `POST /api/simulation/speed?value=<simSecondsPerRealSecond>` | Set fast-forward speed (e.g. `86400` = 1 simulated day/sec). |
| `POST /api/simulation/scenario/{name}` | Run a presenter scenario: `new-referral`, `advance-referral`, `send-at-risk`, `resolve-risk`. |
| `POST /api/admin/reset` | **One-click demo reset** — pauses the simulation, wipes all data, re-applies the deterministic seed, and resets the clock to the 2026-06-29 anchor. Returns `{ status: "reset", seededReferrals }`. (UI guards with a confirm.) |

Returns `502` with an error body when the generator is unreachable. The queue, workbench, and
dashboard views poll every ~8s so changes from the event stream appear live.

