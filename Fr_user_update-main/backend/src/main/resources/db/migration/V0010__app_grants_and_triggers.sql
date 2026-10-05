-- Grants and triggers for the app schema, extending the role-separation principle AD-005
-- report §5 establishes for audit (fru_migrator owns everything, fru_app gets exactly what
-- each table's role in the journey requires) to app, which the report does not spell out
-- explicitly the way it does for audit. Mirrors the hygiene V0001 already applied to audit.

REVOKE ALL ON SCHEMA app FROM PUBLIC;
GRANT USAGE ON SCHEMA app TO fru_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fru_migrator IN SCHEMA app
  REVOKE ALL ON TABLES FROM PUBLIC;

-- status_code: a fixed, migration-maintained code set (like a small enum table). Read-only
-- to the application.
GRANT SELECT ON app.status_code TO fru_app;

-- profile: never deleted (90-day retention nulls PII in place, see V0005). No DELETE.
GRANT SELECT, INSERT, UPDATE ON app.profile TO fru_app;

-- profile_customer_data: device-authoritative, overwritten in place on reconcile. No DELETE —
-- the row's lifetime matches the profile's.
GRANT SELECT, INSERT, UPDATE ON app.profile_customer_data TO fru_app;

-- profile_income_source: a multi-select set the customer can change at stage 4; the app
-- replaces the set (delete rows no longer selected, insert newly selected ones).
GRANT SELECT, INSERT, UPDATE, DELETE ON app.profile_income_source TO fru_app;

-- profile_channel: backend-authoritative state, updated across stage 2's verification flow.
GRANT SELECT, INSERT, UPDATE ON app.profile_channel TO fru_app;

-- otp_challenge: issued and later marked consumed. No DELETE — history of challenges is
-- harmless to retain and useful for lockout/resend accounting.
GRANT SELECT, INSERT, UPDATE ON app.otp_challenge TO fru_app;

-- identity_cycle / scan_result / face_result / registry_result / artifact_ref: state machines
-- driven by the stage 8-10 chain (pending -> active/superseded, staged -> committed, etc.).
GRANT SELECT, INSERT, UPDATE ON app.identity_cycle    TO fru_app;
GRANT SELECT, INSERT, UPDATE ON app.scan_result       TO fru_app;
GRANT SELECT, INSERT, UPDATE ON app.face_result       TO fru_app;
GRANT SELECT, INSERT, UPDATE ON app.registry_result   TO fru_app;
GRANT SELECT, INSERT, UPDATE ON app.artifact_ref      TO fru_app;

-- omni_check: an append-only log of ProcessOmniCheckAct outcomes. No UPDATE/DELETE.
GRANT SELECT, INSERT ON app.omni_check TO fru_app;

-- profile_status_history: append-only, per AD-005 report §4.3 ("operationally insert-only").
-- No UPDATE/DELETE grant, AND (below) a trigger that blocks mutation even for the table
-- owner, the same defence-in-depth reasoning as audit Layer 2.
GRANT SELECT, INSERT ON app.profile_status_history TO fru_app;

-- The report says "the same reject_mutation trigger is applied" to this table. Reusing
-- audit.reject_mutation() verbatim would misreport the schema in its error message (it
-- hardcodes the literal string 'audit.' ahead of TG_TABLE_NAME). This function is the same
-- mechanism with a schema-correct message.
CREATE FUNCTION app.reject_history_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '%.% is append-only; % is not permitted', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
    USING ERRCODE = '42501';
END
$$;

CREATE TRIGGER profile_status_history_immutable
  BEFORE UPDATE OR DELETE ON app.profile_status_history
  FOR EACH ROW EXECUTE FUNCTION app.reject_history_mutation();

CREATE TRIGGER profile_status_history_no_truncate
  BEFORE TRUNCATE ON app.profile_status_history
  FOR EACH STATEMENT EXECUTE FUNCTION app.reject_history_mutation();
