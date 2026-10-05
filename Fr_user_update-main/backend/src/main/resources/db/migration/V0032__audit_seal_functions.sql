-- S2-04: audit seal creation, rendering, export-marking, and seal-vs-chain verification.
-- See docs/sessions/2026-08-27-s2-04-audit-seal.md for the design reasoning, in particular
-- why seal_verify_prefix() below must recompute purely from raw audit_event content and never
-- trust audit_event.content_hash/row_hash or audit_chain.head_hash — those columns are exactly
-- what a rewriter with table-owner access could recompute forward to look self-consistent
-- again after tampering a row inside an already-sealed range (the "recompute attack").

-- Layer 3 bypass for creating these functions in schema audit — see V0031's header comment.
SET LOCAL fru.migration_in_progress = 'on';

-- The only permitted update on audit_seal, once written: a ONE-TIME NULL -> value set of
-- exported_at/export_note, recording that the seal actually left the database. Every other
-- column, and every UPDATE after the first, is rejected — mirrors audit.artifact_guard()'s
-- "the only permitted update is X" shape from V0003. Without this, audit_seal would be the
-- weak link: a rewriter could tamper audit_event AND patch the seal to match.
CREATE FUNCTION audit.seal_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'UPDATE' THEN
    RAISE EXCEPTION 'audit.audit_seal is append-only' USING ERRCODE = '42501';
  END IF;
  IF NEW.seal_id          IS DISTINCT FROM OLD.seal_id
     OR NEW.sealed_at      IS DISTINCT FROM OLD.sealed_at
     OR NEW.chain_count    IS DISTINCT FROM OLD.chain_count
     OR NEW.seal_hash      IS DISTINCT FROM OLD.seal_hash
     OR NEW.prev_seal_hash IS DISTINCT FROM OLD.prev_seal_hash
     OR OLD.exported_at IS NOT NULL   -- already marked exported; one-time only
     OR NEW.exported_at IS NULL       -- the only permitted update sets it
  THEN
    RAISE EXCEPTION
      'the only permitted update on audit.audit_seal is a one-time exported_at/export_note set'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END
$$;

-- Walks audit.audit_event for one chain from seq=1 up to p_upto_seq, recomputing content_hash
-- and row_hash from raw column values exactly as audit.chain_append()/audit.verify_chain() do
-- (V0003) — but returns the recomputed hash for the CALLER to compare, rather than comparing
-- against any stored hash column itself. This is deliberate: it never reads
-- audit_event.content_hash, audit_event.row_hash, or audit_chain.head_hash as ground truth,
-- so it cannot be fooled by a rewriter who recomputed those columns to be internally
-- consistent with tampered content.
CREATE FUNCTION audit.seal_verify_prefix(p_chain uuid, p_upto_seq bigint)
RETURNS TABLE (ok boolean, computed_hash bytea, checked bigint, reason text)
LANGUAGE plpgsql AS $$
DECLARE
  chain_row        audit.audit_chain%ROWTYPE;
  rec              record;
  prev             bytea;
  a_hash           bytea;
  canon            text;
  computed_content bytea;
  n                bigint := 0;
  expected_seq     bigint := 0;
BEGIN
  SELECT * INTO chain_row FROM audit.audit_chain WHERE chain_id = p_chain;
  IF NOT FOUND THEN
    RETURN QUERY SELECT false, NULL::bytea, 0::bigint, 'unknown chain'::text;
    RETURN;
  END IF;

  -- Genesis, recomputed the same way audit.chain_genesis() (V0003) originally set it.
  prev := sha256(
    convert_to(
      chain_row.chain_id::text || '|' || chain_row.chain_kind || '|'
        || coalesce(chain_row.subject_id, ''),
      'UTF8'
    )
  );

  FOR rec IN
    SELECT * FROM audit.audit_event
     WHERE chain_id = p_chain AND seq <= p_upto_seq
     ORDER BY seq
  LOOP
    expected_seq := expected_seq + 1;
    n := n + 1;

    IF rec.seq <> expected_seq THEN
      RETURN QUERY SELECT false, NULL::bytea, n,
        format('sequence gap inside sealed prefix: expected %s got %s', expected_seq, rec.seq);
      RETURN;
    END IF;

    a_hash := NULL;
    IF rec.artifact_id IS NOT NULL THEN
      SELECT sha256 INTO a_hash FROM audit.audit_artifact WHERE artifact_id = rec.artifact_id;
    END IF;

    canon := concat_ws(
      E'\x1e',
      rec.chain_id::text,
      rec.seq::text,
      to_char(rec.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
      rec.event_type,
      rec.actor_kind,
      coalesce(rec.actor_id, ''),
      coalesce(rec.profile_id::text, ''),
      coalesce(rec.session_id::text, ''),
      coalesce(rec.request_id::text, ''),
      coalesce(rec.idempotency_key, ''),
      rec.payload_json,
      coalesce(encode(a_hash, 'hex'), '')
    );

    computed_content := sha256(convert_to(canon, 'UTF8'));
    prev := sha256(prev || computed_content);
  END LOOP;

  IF n <> p_upto_seq THEN
    RETURN QUERY SELECT false, NULL::bytea, n,
      format('chain has fewer events than sealed inside the prefix: found %s, expected %s', n, p_upto_seq);
    RETURN;
  END IF;

  RETURN QUERY SELECT true, prev, n, 'ok'::text;
END
$$;

-- Creates one seal covering every chain in audit.audit_chain at call time: an aggregate row in
-- audit_seal (unchanged shape from V0002) plus one audit_seal_chain row per chain recording
-- its head_seq/head_hash/row_count. prev_seal_hash chains this seal to the previous one (or a
-- fixed genesis constant for the first seal ever), so the sequence of seals is itself
-- tamper-evident. Intended to be invoked externally and periodically (no scheduler exists in
-- this codebase — see docs/components/persistence.md) by fru_sealer, e.g.
-- `psql ... -c "SELECT audit.seal_create();"`.
--
-- Two correctness properties that are easy to get wrong and were found live in review:
-- 1. chain_count, seal_hash and the audit_seal_chain rows must all describe the exact same
--    instant, not three separately-snapshotted reads of audit_chain/audit_event (PostgreSQL's
--    default READ COMMITTED takes a fresh snapshot per statement, not per transaction, so a
--    chain_append() landing between separate reads would otherwise produce a seal whose
--    aggregate hash does not match its own per-chain rows — and both tables are append-only,
--    so the mismatch would be permanent and unrepairable). Fixed by capturing everything in
--    ONE INSERT ... SELECT into a temp table, which is a single statement and therefore a
--    single snapshot even for its correlated per-chain row_count subquery.
-- 2. Two overlapping seal_create() calls (a manual run overlapping a future scheduled one)
--    could both read the same prev_seal_hash and both insert, silently forking the seal-of-
--    seals chain. A session-scoped advisory lock serializes callers, mirroring how
--    audit.chain_append() (V0003) uses SELECT ... FOR UPDATE to serialize appends per chain —
--    there is no natural row to lock for "the whole seal sequence", so an advisory lock is
--    the equivalent tool.
CREATE FUNCTION audit.seal_create() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
  v_seal_id     bigint;
  v_prev_hash   bytea;
  v_chain_count int;
  v_seal_hash   bytea;
  v_canon       text := '';
  r             record;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('audit.seal_create'));

  CREATE TEMP TABLE IF NOT EXISTS seal_create_snapshot (
    chain_id  uuid PRIMARY KEY,
    head_seq  bigint,
    head_hash bytea,
    row_count bigint
  ) ON COMMIT DROP;
  TRUNCATE seal_create_snapshot;

  -- One statement, one snapshot: the correlated subquery and the outer scan of audit_chain
  -- are evaluated against the same READ COMMITTED snapshot, so row_count is guaranteed
  -- consistent with head_seq for every chain captured here.
  INSERT INTO seal_create_snapshot (chain_id, head_seq, head_hash, row_count)
  SELECT c.chain_id, c.head_seq, c.head_hash,
         (SELECT count(*) FROM audit.audit_event e
           WHERE e.chain_id = c.chain_id AND e.seq <= c.head_seq)
    FROM audit.audit_chain c;

  SELECT seal_hash INTO v_prev_hash FROM audit.audit_seal ORDER BY seal_id DESC LIMIT 1;
  IF v_prev_hash IS NULL THEN
    v_prev_hash := sha256(convert_to('audit_seal_genesis', 'UTF8'));
  END IF;

  SELECT count(*) INTO v_chain_count FROM seal_create_snapshot;

  FOR r IN SELECT chain_id, head_seq, head_hash FROM seal_create_snapshot ORDER BY chain_id LOOP
    v_canon := v_canon
      || concat_ws(E'\x1e', r.chain_id::text, r.head_seq::text, encode(r.head_hash, 'hex'))
      || E'\x1f';
  END LOOP;

  v_seal_hash := sha256(convert_to(v_canon, 'UTF8'));

  INSERT INTO audit.audit_seal (chain_count, seal_hash, prev_seal_hash)
  VALUES (v_chain_count, v_seal_hash, v_prev_hash)
  RETURNING seal_id INTO v_seal_id;

  INSERT INTO audit.audit_seal_chain (seal_id, chain_id, head_seq, head_hash, row_count)
  SELECT v_seal_id, chain_id, head_seq, head_hash, row_count FROM seal_create_snapshot;

  RETURN v_seal_id;
END
$$;

-- For each chain the given seal covers: recomputes the hash chain purely from raw event
-- content up to the sealed head_seq (via seal_verify_prefix above) and compares the result
-- ONLY against audit_seal_chain.head_hash — a value in a table that is itself insert-only
-- (V0033) and expected to have left the database once exported. Reports:
--   'matches'           — chain unchanged since sealing.
--   'grew_consistently' — sealed prefix intact; more events appended since (expected, fine).
--   'diverged'          — the sealed prefix does not reproduce the sealed hash. This is the
--                         state a tampered-then-recomputed chain produces even when
--                         audit.verify_chain() reports ok=true, because that function trusts
--                         audit_chain.head_hash, which the same rewriter can also overwrite.
-- current_row_count is a COUNT(*), not audit_chain.head_seq or a MAX(seq) — deliberately: this
-- function must not trust anything a rewriter could also have rewritten (see the file header),
-- and audit_chain.head_seq is one such value. It is named for exactly what it is rather than
-- implied to be a verified sequence position; the two coincide only while the chain beyond the
-- sealed prefix is itself gapless, which this function does not re-verify (that is
-- audit.verify_chain()'s job for the live chain, or a future nightly full walk — S2-04 verifies
-- the SEALED prefix and reports whether the chain grew beyond it, nothing more).
CREATE FUNCTION audit.seal_verify(p_seal_id bigint)
RETURNS TABLE (
  chain_id          uuid,
  status            text,
  sealed_head_seq   bigint,
  sealed_head_hash  bytea,
  current_row_count bigint,
  reason            text
)
LANGUAGE plpgsql AS $$
DECLARE
  seal_chain_row record;
  prefix_result  record;
  live_count     bigint;
BEGIN
  FOR seal_chain_row IN
    SELECT sc.chain_id AS c_id, sc.head_seq AS s_seq, sc.head_hash AS s_hash
      FROM audit.audit_seal_chain sc
     WHERE sc.seal_id = p_seal_id
     ORDER BY sc.chain_id
  LOOP
    SELECT count(*) INTO live_count
      FROM audit.audit_event ae
     WHERE ae.chain_id = seal_chain_row.c_id;

    SELECT * INTO prefix_result
      FROM audit.seal_verify_prefix(seal_chain_row.c_id, seal_chain_row.s_seq);

    chain_id          := seal_chain_row.c_id;
    sealed_head_seq   := seal_chain_row.s_seq;
    sealed_head_hash  := seal_chain_row.s_hash;
    current_row_count := live_count;

    IF NOT prefix_result.ok THEN
      status := 'diverged';
      reason := prefix_result.reason;
    ELSIF prefix_result.computed_hash IS DISTINCT FROM seal_chain_row.s_hash THEN
      status := 'diverged';
      reason := 'sealed prefix altered: recomputed hash does not match the seal';
    ELSIF live_count = seal_chain_row.s_seq THEN
      status := 'matches';
      reason := 'ok';
    ELSE
      status := 'grew_consistently';
      reason := 'ok';
    END IF;

    RETURN NEXT;
  END LOOP;
END
$$;

-- Complements seal_verify() (which checks each chain's data against its seal) by checking the
-- SEALS THEMSELVES: recomputes each seal's seal_hash from its own audit_seal_chain rows (the
-- exact formula seal_create() uses) and confirms prev_seal_hash correctly points at the prior
-- seal (or the genesis constant, for the first). Nothing else in this migration re-derives
-- these two columns from their inputs — without this function a corrupted or forked seal-of-
-- seals chain (see the seal_create() header comment) would go undetected.
CREATE FUNCTION audit.seal_verify_history()
RETURNS TABLE (seal_id bigint, ok boolean, reason text)
LANGUAGE plpgsql AS $$
DECLARE
  seal_row      audit.audit_seal%ROWTYPE;
  chain_row     record;
  v_canon       text;
  v_seal_hash   bytea;
  v_prev_hash   bytea;
  v_genesis     bytea := sha256(convert_to('audit_seal_genesis', 'UTF8'));
BEGIN
  v_prev_hash := v_genesis;

  FOR seal_row IN SELECT * FROM audit.audit_seal ORDER BY seal_id LOOP
    v_canon := '';
    FOR chain_row IN
      SELECT sc.chain_id, sc.head_seq, sc.head_hash
        FROM audit.audit_seal_chain sc
       WHERE sc.seal_id = seal_row.seal_id
       ORDER BY sc.chain_id
    LOOP
      v_canon := v_canon
        || concat_ws(E'\x1e', chain_row.chain_id::text, chain_row.head_seq::text,
                      encode(chain_row.head_hash, 'hex'))
        || E'\x1f';
    END LOOP;
    v_seal_hash := sha256(convert_to(v_canon, 'UTF8'));

    seal_id := seal_row.seal_id;

    IF seal_row.prev_seal_hash IS DISTINCT FROM v_prev_hash THEN
      ok := false;
      reason := 'prev_seal_hash does not point at the prior seal';
    ELSIF seal_row.seal_hash IS DISTINCT FROM v_seal_hash THEN
      ok := false;
      reason := 'seal_hash does not match its own audit_seal_chain rows';
    ELSE
      ok := true;
      reason := 'ok';
    END IF;

    -- Chains forward using the RECOMPUTED hash, not the stored one: if this seal's own
    -- seal_hash was itself tampered, that is already reported above, and using the recomputed
    -- value here stops the error from masking or cascading into the next seal's check.
    v_prev_hash := v_seal_hash;
    RETURN NEXT;
  END LOOP;
END
$$;

-- A stable, canonical text rendering of one seal (and its per-chain snapshot rows), meant to
-- be redirected to a file and copied outside the database — see seal_mark_exported() below and
-- docs/components/persistence.md for why that step, not this function, is what actually makes
-- the seal protective.
CREATE FUNCTION audit.seal_render(p_seal_id bigint) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
  s          audit.audit_seal%ROWTYPE;
  out_text   text;
  chain_line text;
BEGIN
  SELECT * INTO s FROM audit.audit_seal WHERE seal_id = p_seal_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown seal %', p_seal_id;
  END IF;

  out_text := format(
    E'seal_id=%s\nsealed_at=%s\nchain_count=%s\nseal_hash=%s\nprev_seal_hash=%s\n---\n',
    s.seal_id,
    to_char(s.sealed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    s.chain_count,
    encode(s.seal_hash, 'hex'),
    encode(s.prev_seal_hash, 'hex')
  );

  FOR chain_line IN
    SELECT format('chain_id=%s head_seq=%s head_hash=%s row_count=%s',
                   sc.chain_id, sc.head_seq, encode(sc.head_hash, 'hex'), sc.row_count)
      FROM audit.audit_seal_chain sc
     WHERE sc.seal_id = p_seal_id
     ORDER BY sc.chain_id
  LOOP
    out_text := out_text || chain_line || E'\n';
  END LOOP;

  RETURN out_text;
END
$$;

-- Records that a seal's rendering actually left the database (copied to WORM storage, printed,
-- emailed, etc. — the destination itself is out of scope for this task, see the session
-- report). Enforced as one-time by audit.seal_guard() above.
CREATE FUNCTION audit.seal_mark_exported(p_seal_id bigint, p_note text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE audit.audit_seal
     SET exported_at = clock_timestamp(),
         export_note = p_note
   WHERE seal_id = p_seal_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown seal %', p_seal_id;
  END IF;
END
$$;
