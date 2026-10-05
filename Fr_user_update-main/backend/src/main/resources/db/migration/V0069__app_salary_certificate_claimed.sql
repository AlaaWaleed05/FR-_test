-- BL-122: a profile with no salary certificate is ambiguous between a customer who DECLINED (it is
-- optional and gates nothing) and one who ATTACHED a file whose upload never succeeded. Both leave
-- the identical trace today -- no app.artifact_ref row with kind='salary_certificate' -- so the
-- operator deciding whether to approve cannot tell them apart.
--
-- What is recorded is the CLAIM ("the customer attached a file"), never the decline. The decline is
-- the claim's ABSENCE, and that asymmetry is the whole design:
--
--   * There is no decline affordance to assert from. salary_certificate_field.dart offers camera,
--     picker and retry -- no remove, no skip -- so declining is done by doing nothing. Asserting it
--     would mean inventing a customer-facing control to solve a back-office problem.
--   * It fails in the safe direction. A lost claim reads as "declined", which is exactly today's
--     behaviour, so no regression is reachable. A lost DECLINE assertion would read as "upload
--     failed" and send an operator chasing a customer who simply said no.
--   * The claim rides the Stage 6 POST, which is offline-queued and replayed by the mobile
--     flushPending sweep -- the most reliable channel in the journey. A separate decline call has
--     no such replay.
--
-- A timestamptz and not a boolean: it records WHEN the customer told us they had an attachment, and
-- NULL is then unambiguously "no claim on record" rather than a default that could be mistaken for
-- a positive "declined".
--
-- Written by JdbcDataEntryRepository.UPDATE_STAGE6 as an ordinary full replace, with one
-- qualification: only a client that actually SENT the field replaces it. A client built before this
-- field existed sends nothing, and "did not say" must not be written as "said no". An explicit
-- false DOES clear it, deliberately -- see BL-143: an AD-008 device-less re-entry corrects every
-- other column on this row from the new customer's Stage 6, and this must not be the one column
-- their submission cannot reach, or a previous person's claim would be reported about them for
-- ever.
--
-- No new grant: fru_app already holds SELECT, INSERT, UPDATE on app.profile_customer_data (V0010),
-- which is exactly what this write needs.
--
-- NOT settled here, deliberately: what an operator should DO about a claim with no artifact. Ruled
-- by the product owner (2026-09-13) to be nothing -- the certificate stays optional, gates nothing,
-- and the back office is purely informative about it. This column exists so the operator is
-- informed, not so anything acts on it.
ALTER TABLE app.profile_customer_data
  ADD COLUMN salary_certificate_claimed_at timestamptz;

COMMENT ON COLUMN app.profile_customer_data.salary_certificate_claimed_at IS
  'BL-122. When the customer asserted at Stage 6 that they had attached a salary certificate, independent of whether the upload succeeded. Written by the Stage 6 update: true records it (keeping the first timestamp), false clears it, and a client that omits the field leaves it alone. NULL means no claim stands -- which, on a profile whose Stage 6 arrived, is how a decline is recorded. A committed app.artifact_ref row of kind=''salary_certificate'' outranks it in the operator view, so a certificate that arrives late needs no write here. The certificate remains optional and gates nothing.';
