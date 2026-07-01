# Phase 1 Implementation Plan

## Objective

Build the first version of HealthRx: a realistic specialty pharmacy care operations console backed by Spring Boot, React, and Postgres, deployable as a single Cloud Foundry application.

## Build-Readiness Contracts

The following documents are part of the Phase 1 implementation contract:

- [Data Model](data-model.md)
- [Metric Definitions](metric-definitions.md)
- [API Contracts](api-contracts.md)

If implementation discovers a needed change to schema, metric formulas, or API shapes, update these documents in the same change as the implementation.

## Success Criteria

Phase 1 is successful when:

- HealthRx can be built from a clean checkout.
- HealthRx can be pushed to at least one Tanzu Platform for Cloud Foundry foundation.
- A newly provisioned Postgres instance is initialized through migrations.
- The UI shows realistic specialty pharmacy workflows.
- Demo users can move referrals through access milestones.
- Demo users can inspect a patient therapy workbench.
- The dashboard reflects current database state.
- The implementation is ready for Phase 2 event ingestion.
- Tests cover workflow transitions, metric calculations, API contracts, migrations, and the production asset build.

## Milestone 1: Project Skeleton

Create the application foundation.

Tasks:

- Create Spring Boot backend project.
- Create React/Vite frontend project.
- Configure a root Gradle build where `./gradlew clean build` runs the Vite production build and packages frontend assets in the Spring Boot boot jar.
- Add SPA fallback routing for client-side routes while preserving normal `/api/**` errors.
- Add `manifest.yml`.
- Add local development documentation.
- Add `compose.yaml` for local Postgres.
- Add basic CI-friendly build commands.

Deliverables:

- Backend starts locally.
- Frontend starts locally.
- Production artifact can be built.
- Cloud Foundry manifest exists.
- Local setup works from a clean checkout with Docker or Podman available.

## Milestone 2: Database And Domain Model

Create the initial Postgres schema and fictional seed data.

Tasks:

- Add Flyway.
- Configure local Postgres connection using `compose.yaml`.
- Configure Cloud Foundry service binding support.
- Create initial schema migration.
- Create seed data for clinics, care team members, patients, medications, payers, referrals, referral notes, tasks, fills, outreach events, and interventions.
- Add repository and service layer for core workflows.
- Implement the status transition graph from the data model.

Initial domain concepts:

- Clinic
- Patient
- Medication
- Payer
- Referral
- Referral status history
- Referral note
- Therapy
- Fill
- Task
- Outreach event
- Clinical intervention

Deliverables:

- App starts against a blank Postgres database.
- Migrations create schema and load the deterministic target seed data set.
- No manual SQL setup is required.
- Invalid referral status transitions are rejected by backend services.

## Milestone 3: JSON API

Expose the core HealthRx workflows through JSON APIs.

Candidate endpoints:

```text
GET    /api/referrals
GET    /api/referrals/{id}
PATCH  /api/referrals/{id}/status
POST   /api/referrals/{id}/notes

GET    /api/patients/{id}
GET    /api/patients/{id}/timeline
POST   /api/patients/{id}/outreach
POST   /api/patients/{id}/interventions

GET    /api/dashboard/summary
GET    /api/dashboard/trends
GET    /api/lookups
```

Deliverables:

- APIs support the three Phase 1 views.
- Workflow transitions persist to Postgres.
- Dashboard data is calculated from current data, not hard-coded UI constants.
- API responses follow [API Contracts](api-contracts.md).

## Milestone 4: Frontend Experience

Build the HealthRx user interface.

Views:

- Enrollment and Access Queue
- Patient Therapy Workbench
- Outcomes Dashboard

Expected UI behavior:

- Filter referrals by status, clinic, disease state, payer, and owner.
- Open a referral/patient detail panel.
- Advance referral status.
- Add notes, outreach events, or interventions.
- Show patient journey timeline.
- Show refill risk and adherence indicators.
- Show dashboard metrics derived from API responses.
- Show useful empty, loading, and error states for queue, workbench, and dashboard data.

Deliverables:

- Polished demo UI.
- Responsive enough for laptop demo use.
- Real API integration.
- No static-only mock screens.
- Main laptop demo viewport is polished at 1440px width and usable at 1024px width.

## Milestone 5: Cloud Foundry Deployment

Make the app portable across foundations.

Tasks:

- Build deployable artifact.
- Validate service binding behavior with Postgres.
- Use manifest variables for app name, route, and service instance names.
- Document deployment commands.
- Document foundation-specific vars files.
- Keep exact Postgres marketplace offering and plan outside source code in a vars file.

Deliverables:

- `cf push` workflow documented.
- App can be deployed to a foundation with a managed Postgres service.
- Re-deploying the app does not require manual database work.

## Milestone 6: Phase 2 Readiness

Prepare the app for synthetic event ingestion without building the generator yet.

Tasks:

- Define the first event vocabulary.
- Document how events map to database updates.
- Keep status transitions and timeline entries centralized in backend services.
- Add clear logging for workflow transitions.

Candidate Phase 2 events:

- `ReferralCreated`
- `BenefitsInvestigationStarted`
- `PriorAuthorizationSubmitted`
- `PriorAuthorizationApproved`
- `PriorAuthorizationDenied`
- `FinancialAssistanceFound`
- `PrescriptionFilled`
- `DeliveryScheduled`
- `RefillDue`
- `RefillMissed`
- `PatientOutreachLogged`
- `ClinicalInterventionCreated`

Deliverables:

- Event vocabulary documented.
- Backend workflow services are ready to be called by RabbitMQ consumers in Phase 2.

## Initial Data Set

Use fictional data that resembles specialty pharmacy operations.

The seed data must be deterministic and migration-managed. Target volume for the first build:

- 4 clinics.
- 8 care team members.
- 12 payers.
- 12 fictional medications across the 4 demo domains.
- 80 patients.
- 100 referrals distributed across the status graph.
- At least 40 active therapies.
- At least 160 fills across active therapies.
- At least 80 tasks, including overdue and owner-distributed work.
- At least 60 outreach events.
- At least 30 clinical interventions.
- At least 40 referral notes.

Referral status distribution should include enough rows in every non-cancelled status to make filters and dashboard status counts meaningful.

Recommended demo domains:

- Oncology
- Rheumatology
- Multiple sclerosis
- Gastroenterology

Recommended demo medications:

- Fictional brand names.
- Avoid using real patient data.
- Real disease-state labels are acceptable for realism.

Recommended operational scenarios:

- High-copay oncology referral requiring financial assistance.
- Rheumatology referral waiting on prior authorization.
- Multiple sclerosis patient with an upcoming refill risk.
- Gastroenterology patient with a missing lab task.

## Technical Defaults

Use:

- Java 17.
- Spring Boot.
- Gradle.
- Spring Data JDBC.
- React, Vite, and TypeScript.
- Postgres.
- Flyway.
- Single Cloud Foundry app in Phase 1.
- Fictional medication names.
- No demo login in the first build unless a stakeholder specifically requests it.

## Foundation-Specific Deployment Inputs

The exact Postgres marketplace service name and plan vary by foundation and should be captured in a local vars file, not hard-coded. The implementation should include a template such as:

```text
cf-vars/example.yml
```

Expected variables:

```text
app_name
route
postgres_service_instance
postgres_service_offering
postgres_service_plan
```

## Testing Strategy

Minimum Phase 1 coverage:

- Unit tests for referral status transition validation.
- Unit tests for adherence, refill risk, time-to-therapy, and PA turnaround calculations.
- Repository or integration tests for Flyway migrations against Postgres, preferably with Testcontainers.
- Controller tests for API success and error contracts.
- Frontend component tests for queue/workbench/dashboard empty, loading, error, and populated states.
- A production build check that verifies frontend assets are packaged into the Spring Boot artifact.

## Phase 1 Non-Goals

- RabbitMQ integration.
- Synthetic data generator.
- AI agents.
- Real EMR, payer, pharmacy, or manufacturer integration.
- Production-grade authentication and authorization.
- HIPAA production controls.
