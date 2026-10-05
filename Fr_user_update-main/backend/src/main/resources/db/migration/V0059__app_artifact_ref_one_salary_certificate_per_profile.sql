-- S4-06 (BL-022): mirrors V0046's signature precedent exactly. app.artifact_ref's only other
-- relevant constraint is UNIQUE (cycle_id, kind) (V0008); 'salary_certificate' rows always have
-- cycle_id NULL (V0008's own comment: "for the salary certificate, which has no cycle"), and
-- Postgres UNIQUE does not dedupe NULLs -- without this index, customer.md Stage 6's "the
-- profile submits without it and the attachment lands whenever it can" replace-on-retry path
-- would silently accumulate one retained-PII row per upload attempt instead of replacing.
--
-- A partial unique index scoped to kind='salary_certificate' closes this the same way V0046
-- already closed it for 'signature' -- scoped to profile_id, since a salary certificate has no
-- cycle_id to key on either.
CREATE UNIQUE INDEX artifact_ref_one_salary_certificate_per_profile
  ON app.artifact_ref (profile_id)
  WHERE kind = 'salary_certificate';
