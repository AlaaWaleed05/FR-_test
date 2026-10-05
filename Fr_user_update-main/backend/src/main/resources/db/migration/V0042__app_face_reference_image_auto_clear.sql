-- Enforces, in the database rather than trusting every current and future service-layer
-- call site to remember, that V0041's bridging column is actually cleared once it is no
-- longer needed -- the same reasoning already behind the status-transition guard (V0020),
-- the four-eyes rule (AD-005 report Sec4.3) and append-only enforcement in this schema:
-- an invariant this important does not live in Java alone.
--
-- Two clearing paths get a trigger here because both correspond to a real row transition
-- this schema already has a column for. A third path -- face-match SUCCESS -- has no such
-- transition (identity_cycle.state stays 'active', app.profile.status stays 'in_progress')
-- and is therefore cleared explicitly by LivenessService instead; see
-- docs/components/uqudo-sdk.md and the S3-13 session report.

-- Path 1: the identity cycle holding the reference image is superseded (customer.md Stage
-- 9 "wrong number" rescans; also identity_cycle's own defensive supersede-before-insert).
-- Covers every current and future caller of supersedeActiveCycleIfAny automatically.
CREATE FUNCTION app.clear_face_reference_image_on_supersede() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.state = 'superseded' AND OLD.state IS DISTINCT FROM 'superseded' THEN
    UPDATE app.scan_result
       SET face_reference_image = NULL
     WHERE cycle_id = NEW.cycle_id
       AND face_reference_image IS NOT NULL;
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER identity_cycle_clears_face_reference_image
  AFTER UPDATE ON app.identity_cycle
  FOR EACH ROW EXECUTE FUNCTION app.clear_face_reference_image_on_supersede();

-- Path 2: the profile itself reaches ANY terminal status -- submitted (customer.md Stage
-- 12), terminated_registry_mismatch (Stage 9 "wrong details"), and, not incidentally,
-- whatever future manual-completion / approve / reject code the back office eventually
-- adds (out of scope for this task, but this trigger does not need that code to exist to
-- already be correct for it). Joins status_code rather than hardcoding the terminal set in
-- a second place -- same discipline app.status_code.is_terminal already exists to enforce.
CREATE FUNCTION app.clear_face_reference_image_on_terminal() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_is_terminal boolean;
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    SELECT is_terminal INTO v_is_terminal FROM app.status_code WHERE code = NEW.status;
    IF v_is_terminal THEN
      UPDATE app.scan_result sr
         SET face_reference_image = NULL
        FROM app.identity_cycle ic
       WHERE sr.cycle_id = ic.cycle_id
         AND ic.profile_id = NEW.profile_id
         AND sr.face_reference_image IS NOT NULL;
    END IF;
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER profile_clears_face_reference_image_on_terminal
  AFTER UPDATE ON app.profile
  FOR EACH ROW EXECUTE FUNCTION app.clear_face_reference_image_on_terminal();

-- No new grant: both functions run UPDATE app.scan_result under the invoking role's own
-- privileges (default SECURITY INVOKER, no DEFINER clause), and fru_app already holds
-- UPDATE on app.scan_result and app.identity_cycle (V0010).
