-- The signed-in demo user is Trevor Putbrese — the presenter actually driving the app. V8
-- collapsed the care team to one active member (seeded as Maya Patel); this renames that member
-- in place so every owner reference, task assignment, and history entry reads as Trevor without
-- touching any foreign keys. The V2 seed itself stays untouched (Flyway checksums).
update care_team_members
set display_name = 'Trevor Putbrese',
    email = 'trevor.putbrese@healthrx.example'
where id = 'e6c696c4-e376-584d-8cdf-be32052b0dcc';
