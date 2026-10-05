-- Closes S2-02 §7b / R-030: a raw UPDATE could move app.profile.status through any pair,
-- with no legal-transition check and no matching app.profile_status_history row.
-- customer.md Stage 11 and operator.md "Every status transition" both state the governing
-- rule verbatim: "There are no silent state changes." Enforced here in the database, not in
-- a service layer that does not exist yet -- the same reasoning that put the four-eyes rule
-- in SQL rather than the UI (AD-005 report §4.3, V0009).

-- Legal-transitions table. from_status is nullable: NULL means "profile creation", i.e.
-- there is no prior row to compare against. Pairs derived from docs/journeys/customer.md
-- and docs/journeys/operator.md -- see the S2-05 session report for the line-by-line
-- citation of each row. No transition is invented beyond what those documents state or
-- directly imply (manual completion's source status is unconstrained by operator.md, so
-- every non-terminal status is accepted as a source into 'submitted').
CREATE TABLE app.status_transition (
  transition_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  from_status   text REFERENCES app.status_code(code),
  to_status     text NOT NULL REFERENCES app.status_code(code),
  note          text NOT NULL,
  CONSTRAINT status_transition_pair UNIQUE NULLS NOT DISTINCT (from_status, to_status)
);

INSERT INTO app.status_transition (from_status, to_status, note) VALUES
  (NULL,                'in_progress',
    'customer.md Stage 1b: "the session is created" -- profile creation, no prior status'),
  ('in_progress',       'awaiting_registry',
    'customer.md Stage 9: "Civil Registry unreachable or returning nothing"'),
  ('awaiting_registry', 'in_progress',
    'customer.md Stage 9/12: "On resume the backend simply retries the lookup"'),
  ('in_progress',       'blocked_scan',
    'customer.md Stage 8: "Either budget exhausted -> temporary block"'),
  ('blocked_scan',      'in_progress',
    'customer.md Stage 8/12: 24h block expires, "customer returns and resumes at the scan"'),
  ('in_progress',       'blocked_liveness',
    'customer.md Stage 10: "Budget exhausted ... temporary block on this stage only"'),
  ('blocked_liveness',  'in_progress',
    'customer.md Stage 10/12: 24h block expires, resumes at liveness'),
  ('in_progress',       'terminated_registry_mismatch',
    'customer.md Stage 9: "The number is right but my details are wrong" -> terminal'),
  ('in_progress',       'abandoned',
    'customer.md Policy values: "30 days of inactivity -> status abandoned"'),
  ('awaiting_registry', 'abandoned',
    'same 30-day rule; operator.md groups abandoned with the other in-flight statuses'),
  ('blocked_scan',      'abandoned',
    'same 30-day rule'),
  ('blocked_liveness',  'abandoned',
    'same 30-day rule'),
  ('abandoned',         'in_progress',
    'customer.md Stage 1a: "An incomplete profile exists" continues through 1b and the review flow; abandoned is classified "In flight" by operator.md, so it is an incomplete profile under this rule'),
  ('in_progress',       'submitted',
    'customer.md Stage 11: "Backend sets status to submitted" (digital completion)'),
  ('awaiting_registry', 'submitted',
    'operator.md "Manual completion": no precondition status stated, any in-flight status is a legal source'),
  ('blocked_scan',      'submitted',
    'operator.md "Manual completion": no precondition status stated, any in-flight status is a legal source'),
  ('blocked_liveness',  'submitted',
    'operator.md "Manual completion": no precondition status stated, any in-flight status is a legal source'),
  ('abandoned',         'submitted',
    'operator.md "Manual completion": no precondition status stated, any in-flight status is a legal source'),
  ('submitted',         'approved',
    'operator.md "Review": "Approve -> status becomes approved"'),
  ('submitted',         'rejected',
    'operator.md "Review": "Reject -> status becomes rejected"'),
  ('rejected',          'approved',
    'operator.md "Re-approving a rejected profile": "the operator can then move the profile rejected -> approved"');

-- No row anywhere has from_status IN ('approved','terminated_registry_mismatch'): both are
-- terminal with no outgoing transition. 'rejected' has exactly the one outgoing arc above;
-- no document text describes any other way out of it, or any way out of
-- terminated_registry_mismatch, so none is added.

CREATE FUNCTION app.check_status_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_old text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_old := NULL;
  ELSE
    v_old := OLD.status;
    IF v_old IS NOT DISTINCT FROM NEW.status THEN
      RETURN NEW; -- no status change on this row -- e.g. resume_stage/last_activity_at only
    END IF;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM app.status_transition st
     WHERE st.from_status IS NOT DISTINCT FROM v_old
       AND st.to_status = NEW.status
  ) THEN
    RAISE EXCEPTION 'illegal status transition on app.profile %: % -> %',
      NEW.profile_id, coalesce(v_old, '<new>'), NEW.status
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER profile_status_transition_guard
  BEFORE INSERT OR UPDATE ON app.profile
  FOR EACH ROW EXECUTE FUNCTION app.check_status_transition();

-- The matching-history-row requirement is a SEPARATE, DEFERRED constraint trigger rather
-- than folded into the BEFORE trigger above. The AD-005 report's own approve statement
-- (V0009's comment, proven live in S2-02 §7c) is a single UPDATE app.profile; the caller is
-- expected to INSERT the matching app.profile_status_history row as a second statement in
-- the same transaction, and the order between the two is not fixed. A BEFORE trigger would
-- fire before the history row necessarily exists. DEFERRABLE INITIALLY DEFERRED runs this
-- check once at COMMIT instead, after every statement in the transaction has run, in
-- whichever order the caller issued them -- the "constraint trigger deferred to commit"
-- pattern. This adds no new obligation to the existing approve/reject statements beyond
-- what the journey's governing rule ("no silent state changes") already required of their
-- caller: insert the history row before committing.
--
-- The check binds to the MOST RECENT history row for the profile (highest seq), not to
-- "does any row with this (from,to) pair exist anywhere in history" -- the latter would let
-- a repeatable pair (e.g. in_progress <-> blocked_scan, which is legal in both directions)
-- reuse a stale row from an earlier, unrelated hop and pass with no new history row at all
-- (found live in review). Binding to the latest row assumes at most one status change per
-- profile per transaction, which is already how every write path in this schema is built
-- (the AD-005 approve/reject statements are each a single UPDATE paired with a single
-- history INSERT).
CREATE FUNCTION app.require_status_history() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_old text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_old := NULL;
  ELSE
    v_old := OLD.status;
    IF v_old IS NOT DISTINCT FROM NEW.status THEN
      RETURN NEW;
    END IF;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM app.profile_status_history h
     WHERE h.profile_id = NEW.profile_id
       AND h.to_status = NEW.status
       AND h.from_status IS NOT DISTINCT FROM v_old
       AND h.seq = (SELECT max(h2.seq) FROM app.profile_status_history h2
                     WHERE h2.profile_id = NEW.profile_id)
  ) THEN
    RAISE EXCEPTION 'status change on app.profile % (% -> %) has no matching (most recent) app.profile_status_history row',
      NEW.profile_id, coalesce(v_old, '<new>'), NEW.status
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER profile_status_requires_history
  AFTER INSERT OR UPDATE ON app.profile
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app.require_status_history();

GRANT SELECT ON app.status_transition TO fru_app;
