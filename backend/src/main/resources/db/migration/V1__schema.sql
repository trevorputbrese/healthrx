-- HealthRx Phase 1 schema. See data-model.md for the build contract.
-- Conventions: uuid primary keys (app-generated), timestamptz timestamps,
-- numeric(12,2) money, text enums with check constraints mirrored as Java enums.

create table clinics (
    id          uuid primary key,
    name        text        not null,
    region      text        not null,
    created_at  timestamptz not null
);

create table payers (
    id          uuid primary key,
    name        text        not null,
    payer_type  text        not null,
    created_at  timestamptz not null
);

create table care_team_members (
    id           uuid primary key,
    display_name text        not null,
    role         text        not null,
    email        text,
    active       boolean     not null default true,
    created_at   timestamptz not null,
    constraint uq_care_team_email unique (email)
);

create table medications (
    id                   uuid primary key,
    name                 text        not null,
    disease_state        text        not null,
    route                text        not null,
    limited_distribution boolean     not null default false,
    active               boolean     not null default true,
    created_at           timestamptz not null,
    constraint ck_medications_disease_state check (disease_state in
        ('Oncology', 'Rheumatology', 'Multiple sclerosis', 'Gastroenterology'))
);

create table patients (
    id              uuid primary key,
    demo_mrn        text        not null,
    first_name      text        not null,
    last_name       text        not null,
    date_of_birth   date        not null,
    disease_state   text        not null,
    clinic_id       uuid        not null references clinics (id),
    primary_owner_id uuid       not null references care_team_members (id),
    payer_id        uuid        not null references payers (id),
    created_at      timestamptz not null,
    constraint uq_patients_demo_mrn unique (demo_mrn),
    constraint ck_patients_disease_state check (disease_state in
        ('Oncology', 'Rheumatology', 'Multiple sclerosis', 'Gastroenterology'))
);

create table therapies (
    id                      uuid primary key,
    patient_id              uuid        not null references patients (id),
    medication_id           uuid        not null references medications (id),
    diagnosis               text        not null,
    disease_state           text        not null,
    status                  text        not null,
    start_date              date,
    end_date                date,
    current_refill_due_date date,
    created_at              timestamptz not null,
    constraint ck_therapies_status check (status in
        ('PENDING_ACCESS', 'ACTIVE', 'PAUSED', 'DISCONTINUED')),
    constraint ck_therapies_disease_state check (disease_state in
        ('Oncology', 'Rheumatology', 'Multiple sclerosis', 'Gastroenterology'))
);

create table referrals (
    id                                 uuid primary key,
    referral_number                    text        not null,
    patient_id                         uuid        not null references patients (id),
    clinic_id                          uuid        not null references clinics (id),
    therapy_id                         uuid        references therapies (id),
    medication_id                      uuid        not null references medications (id),
    payer_id                           uuid        not null references payers (id),
    owner_id                           uuid        not null references care_team_members (id),
    current_status                     text        not null,
    priority                           text        not null,
    received_at                        timestamptz not null,
    benefits_investigation_started_at  timestamptz,
    pa_required                        boolean     not null,
    pa_submitted_at                    timestamptz,
    pa_decided_at                      timestamptz,
    financial_assistance_required      boolean     not null,
    financial_assistance_secured_amount numeric(12, 2) not null default 0,
    copay_amount                       numeric(12, 2) not null default 0,
    ready_to_fill_at                   timestamptz,
    delivery_scheduled_at              timestamptz,
    active_therapy_at                  timestamptz,
    closed_at                          timestamptz,
    created_at                         timestamptz not null,
    updated_at                         timestamptz not null,
    constraint uq_referrals_referral_number unique (referral_number),
    constraint ck_referrals_current_status check (current_status in
        ('ELIGIBILITY_IDENTIFIED', 'BENEFITS_INVESTIGATION', 'PRIOR_AUTH_SUBMITTED',
         'PRIOR_AUTH_APPROVED', 'PRIOR_AUTH_DENIED', 'FINANCIAL_ASSISTANCE_REVIEW',
         'READY_TO_FILL', 'DELIVERY_SCHEDULED', 'ACTIVE_THERAPY', 'CANCELLED')),
    constraint ck_referrals_priority check (priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);

create table referral_status_history (
    id                uuid primary key,
    referral_id       uuid        not null references referrals (id),
    from_status       text,
    to_status         text        not null,
    changed_at        timestamptz not null,
    changed_by_id     uuid        references care_team_members (id),
    note              text,
    phase2_event_type text,
    constraint ck_status_history_event check (phase2_event_type is null or phase2_event_type in
        ('ReferralCreated', 'BenefitsInvestigationStarted', 'PriorAuthorizationSubmitted',
         'PriorAuthorizationApproved', 'PriorAuthorizationDenied', 'FinancialAssistanceFound',
         'ReadyToFill', 'DeliveryScheduled', 'TherapyActivated', 'ReferralCancelled',
         'PrescriptionFilled', 'RefillDue', 'RefillMissed', 'PatientOutreachLogged',
         'ClinicalInterventionCreated'))
);

create table referral_notes (
    id          uuid primary key,
    referral_id uuid        not null references referrals (id),
    author_id   uuid        not null references care_team_members (id),
    body        text        not null,
    created_at  timestamptz not null
);

create table tasks (
    id           uuid primary key,
    patient_id   uuid        not null references patients (id),
    referral_id  uuid        references referrals (id),
    owner_id     uuid        not null references care_team_members (id),
    type         text        not null,
    status       text        not null,
    priority     text        not null,
    title        text        not null,
    description  text,
    due_at       timestamptz,
    completed_at timestamptz,
    created_at   timestamptz not null,
    constraint ck_tasks_type check (type in
        ('MISSING_LAB', 'PRIOR_AUTH_RENEWAL', 'PATIENT_CONTACT', 'FINANCIAL_ASSISTANCE',
         'REFILL_FOLLOW_UP', 'CLINICAL_REVIEW')),
    constraint ck_tasks_status check (status in
        ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    constraint ck_tasks_priority check (priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);

create table outreach_events (
    id          uuid primary key,
    patient_id  uuid        not null references patients (id),
    referral_id uuid        references referrals (id),
    owner_id    uuid        not null references care_team_members (id),
    channel     text        not null,
    outcome     text        not null,
    notes       text,
    occurred_at timestamptz not null,
    created_at  timestamptz not null,
    constraint ck_outreach_channel check (channel in ('PHONE', 'SMS', 'EMAIL', 'PORTAL')),
    constraint ck_outreach_outcome check (outcome in
        ('REACHED', 'LEFT_MESSAGE', 'NO_ANSWER', 'DECLINED', 'NEEDS_FOLLOW_UP'))
);

create table clinical_interventions (
    id                uuid primary key,
    patient_id        uuid        not null references patients (id),
    referral_id       uuid        references referrals (id),
    owner_id          uuid        not null references care_team_members (id),
    intervention_type text        not null,
    summary           text        not null,
    occurred_at       timestamptz not null,
    created_at        timestamptz not null,
    constraint ck_intervention_type check (intervention_type in
        ('ADHERENCE_COUNSELING', 'SIDE_EFFECT_MANAGEMENT', 'DOSE_CLARIFICATION',
         'LAB_MONITORING', 'CARE_COORDINATION'))
);

create table fills (
    id                  uuid primary key,
    patient_id          uuid        not null references patients (id),
    therapy_id          uuid        not null references therapies (id),
    referral_id         uuid        references referrals (id),
    fill_number         integer     not null,
    status              text        not null,
    dispensed_at        date,
    days_supply         integer     not null,
    expected_refill_date date       not null,
    created_at          timestamptz not null,
    constraint ck_fills_status check (status in
        ('SCHEDULED', 'DISPENSED', 'DELAYED', 'CANCELLED', 'MISSED')),
    constraint ck_fills_days_supply check (days_supply > 0)
);

-- Indexes supporting queue filters, dashboard rollups, and timeline reads.
create index idx_referrals_current_status on referrals (current_status);
create index idx_referrals_received_at on referrals (received_at);
create index idx_referrals_active_therapy_at on referrals (active_therapy_at);
create index idx_referrals_owner on referrals (owner_id);
create index idx_referrals_patient on referrals (patient_id);
create index idx_patients_clinic on patients (clinic_id);
create index idx_patients_owner on patients (primary_owner_id);
create index idx_therapies_patient_status on therapies (patient_id, status);
create index idx_fills_therapy on fills (therapy_id, status);
create index idx_tasks_owner_status on tasks (owner_id, status);
create index idx_tasks_referral on tasks (referral_id);
create index idx_outreach_patient on outreach_events (patient_id, occurred_at);
create index idx_interventions_patient on clinical_interventions (patient_id, occurred_at);
create index idx_status_history_referral on referral_status_history (referral_id, changed_at);
