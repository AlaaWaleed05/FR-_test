-- Backs stage 12's reference-number generation (customer.md Stage 12: "the reference
-- number, prominently... it is the customer's only artifact once local storage clears").
-- app.profile.reference_number itself already exists (V0005, UNIQUE, NULL until submitted)
-- -- this sequence is the missing generator.
--
-- Format is a PLACEHOLDER: "FRU-" plus a zero-padded 9-digit sequence value, e.g.
-- FRU-000000001. No format is specified anywhere in the journey documents or by the bank;
-- SubmissionService documents this plainly as an operational default, not a settled
-- decision, the same way the seeded status labels are placeholders (V0005's own comment).
-- A plain sequence (not tied to branch or date) is sufficient at this project's stated
-- scale (~100,000 profiles total, PROJECT_PLAN.md Constraints) and keeps the format free of
-- any embedded meaning a future real spec might contradict.
CREATE SEQUENCE app.reference_number_seq;

GRANT USAGE ON SEQUENCE app.reference_number_seq TO fru_app;
