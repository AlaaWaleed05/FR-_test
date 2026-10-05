-- app schema, per AD-005 report §4.3 (status history — the four-eyes rule's own table).

CREATE TABLE app.profile_status_history (
  history_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  profile_id     uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  seq            int  NOT NULL,
  from_status    text REFERENCES app.status_code(code),     -- NULL for the first
  to_status      text NOT NULL REFERENCES app.status_code(code),
  occurred_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  actor_kind     text NOT NULL CHECK (actor_kind IN ('customer','operator','system')),
  actor_id       text,                                      -- operator identity; NULL otherwise
  reason_code    text,                                      -- REJ-01..07, versioned in ref
  reason_version int,
  reason_list    text GENERATED ALWAYS AS ('rejection_reason') STORED,
  internal_note  text,                                      -- never sent to the customer
  is_manual_completion boolean NOT NULL DEFAULT false,      -- drives the four-eyes rule
  audit_event_id bigint NOT NULL REFERENCES audit.audit_event(audit_event_id),
  UNIQUE (profile_id, seq),
  CONSTRAINT operator_named CHECK (actor_kind <> 'operator' OR actor_id IS NOT NULL),
  CONSTRAINT reject_needs_code CHECK (to_status <> 'rejected' OR reason_code IS NOT NULL),
  CONSTRAINT rej07_needs_detail CHECK (reason_code <> 'REJ-07' OR internal_note IS NOT NULL)
  -- FK (reason_list, reason_version, reason_code) REFERENCES ref.reference_item (...) is
  -- DEFERRED to S2-03: ref.reference_item does not exist yet in this session (OUT OF SCOPE).
  -- Add in an S2-03 migration:
  --   ALTER TABLE app.profile_status_history ADD CONSTRAINT reason_code_fk
  --     FOREIGN KEY (reason_list, reason_version, reason_code)
  --     REFERENCES ref.reference_item (list_code, version, item_code);
);

-- This table is deliberately a SECOND copy of information that also lives in the audit trail
-- (audit_event_id binds the two; a mismatch is itself a detectable tampering signal) — see
-- AD-005 report §4.3 for why: operator.md requires searching/filtering on status history
-- without generating an audit_read event per profile-list page view.

-- Four-eyes rule, enforced in the database rather than in the UI — the approve statement is a
-- single conditional UPDATE (AD-005 report §4.3):
--
--   UPDATE app.profile p
--      SET status = 'approved', status_changed_at = clock_timestamp(), row_version = row_version + 1
--    WHERE p.profile_id = :pid
--      AND p.status IN ('submitted','rejected')            -- legal prior states only
--      AND NOT EXISTS (                                     -- four-eyes
--            SELECT 1 FROM app.profile_status_history h
--             WHERE h.profile_id = p.profile_id
--               AND h.is_manual_completion
--               AND h.actor_id = :operator_id);
--
-- Zero rows affected means either a lost race or a four-eyes violation. This makes the
-- control impossible to bypass by calling the API directly. Proven live in the S2-02 session
-- report.
