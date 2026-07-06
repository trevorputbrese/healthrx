-- Trims the seeded referral set from 108 down to 14 for a leaner, easier-to-navigate demo:
-- one referral per (disease state, lifecycle status) combination, covering all 10 statuses
-- across all 4 disease states, plus the 4 scripted scenario referrals (RX-10001..RX-10004)
-- kept unconditionally since presenter scenarios (send-at-risk/resolve-risk, the four
-- generate_seed.py demo beats) target them by patient MRN. Patients themselves are untouched —
-- only their referral history is trimmed, so the Patients directory still shows all 80.
--
-- A referral being removed also removes: its status history/notes (referral_id is NOT NULL
-- there, so orphans are impossible — must delete, not null out), any tasks/outreach/
-- interventions that reference it specifically (referral_id nullable; rows unrelated to any
-- referral are untouched), and — only for ACTIVE_THERAPY referrals — the therapy created for
-- it and that therapy's fills (each ACTIVE_THERAPY referral owns exactly one therapy;
-- confirmed no patient holds more than one ACTIVE_THERAPY referral in the seed).

create temporary table tmp_keep_referrals on commit drop as
    select id from referrals where referral_number in ('RX-10001', 'RX-10002', 'RX-10003', 'RX-10004')
    union
    select id from (
        select r.id,
               row_number() over (partition by p.disease_state, r.current_status
                                   order by r.received_at asc) as rn
        from referrals r
        join patients p on p.id = r.patient_id
        where (p.disease_state, r.current_status) in (
            ('Oncology', 'ELIGIBILITY_IDENTIFIED'),
            ('Oncology', 'READY_TO_FILL'),
            ('Oncology', 'ACTIVE_THERAPY'),
            ('Rheumatology', 'BENEFITS_INVESTIGATION'),
            ('Rheumatology', 'DELIVERY_SCHEDULED'),
            ('Rheumatology', 'FINANCIAL_ASSISTANCE_REVIEW'),
            ('Multiple sclerosis', 'PRIOR_AUTH_APPROVED'),
            ('Multiple sclerosis', 'CANCELLED'),
            ('Gastroenterology', 'PRIOR_AUTH_DENIED'),
            ('Gastroenterology', 'ACTIVE_THERAPY')
        )
    ) ranked
    where rn = 1;

create temporary table tmp_removed_referrals on commit drop as
    select id, therapy_id from referrals where id not in (select id from tmp_keep_referrals);

-- Child rows referencing the referral must go first; referrals.therapy_id itself references
-- therapies (the referring row), so the referral must be deleted BEFORE its therapy — deleting
-- the therapy first would violate the FK from the still-existing referral row.
delete from fills where therapy_id in (
    select therapy_id from tmp_removed_referrals where therapy_id is not null);
delete from referral_status_history where referral_id in (select id from tmp_removed_referrals);
delete from referral_notes where referral_id in (select id from tmp_removed_referrals);
delete from tasks where referral_id in (select id from tmp_removed_referrals);
delete from outreach_events where referral_id in (select id from tmp_removed_referrals);
delete from clinical_interventions where referral_id in (select id from tmp_removed_referrals);

delete from referrals where id in (select id from tmp_removed_referrals);

delete from therapies where id in (
    select therapy_id from tmp_removed_referrals where therapy_id is not null);
