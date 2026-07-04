-- Collapse the demo to a single signed-in user: Maya Patel, Clinical Pharmacist.
-- Eight acting-as personas confused the demo story; every referral, patient, task, and
-- historical touch now belongs to the one person "logged in". The other seven human members
-- are deactivated (not deleted) so any missed reference still resolves; non-human actors
-- (System, AI agents) are untouched. Idempotent — the demo reset re-applies it after reseeding.

-- Maya Patel (from the V2 seed).
-- All other humans = active or not, role not in ('System', 'AI Agent'), id <> Maya's.

update patients set primary_owner_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where primary_owner_id in (select id from care_team_members
                           where role not in ('System', 'AI Agent')
                             and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update referrals set owner_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where owner_id in (select id from care_team_members
                   where role not in ('System', 'AI Agent')
                     and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update tasks set owner_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where owner_id in (select id from care_team_members
                   where role not in ('System', 'AI Agent')
                     and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update outreach_events set owner_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where owner_id in (select id from care_team_members
                   where role not in ('System', 'AI Agent')
                     and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update clinical_interventions set owner_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where owner_id in (select id from care_team_members
                   where role not in ('System', 'AI Agent')
                     and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update referral_notes set author_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where author_id in (select id from care_team_members
                    where role not in ('System', 'AI Agent')
                      and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update referral_status_history set changed_by_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where changed_by_id in (select id from care_team_members
                        where role not in ('System', 'AI Agent')
                          and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update agent_recommendations set decided_by_id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc'
where decided_by_id in (select id from care_team_members
                        where role not in ('System', 'AI Agent')
                          and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc');

update care_team_members set active = false
where role not in ('System', 'AI Agent')
  and id <> 'e6c696c4-e376-584d-8cdf-be32052b0dcc';
