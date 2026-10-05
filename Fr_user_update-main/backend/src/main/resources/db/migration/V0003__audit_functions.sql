-- Layer 2: blanket append-only guard for audit.audit_event. Fires regardless of who issues
-- the UPDATE/DELETE/TRUNCATE — including fru_migrator, the table owner, against whom a
-- REVOKE is meaningless. This is what "catches the owner" (AD-005 report §5).
CREATE FUNCTION audit.reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit.% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
    USING ERRCODE = '42501';
END
$$;

-- Layer 2, narrower: audit_artifact must still allow the lawful 90-day PII purge (body ->
-- NULL, body_purged_at set), with every other column and every other kind of statement
-- rejected. See AD-005 report §5.
CREATE FUNCTION audit.artifact_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'UPDATE' THEN
    RAISE EXCEPTION 'audit.audit_artifact is append-only' USING ERRCODE = '42501';
  END IF;
  IF NEW.artifact_id    IS DISTINCT FROM OLD.artifact_id
     OR NEW.chain_id    IS DISTINCT FROM OLD.chain_id
     OR NEW.kind        IS DISTINCT FROM OLD.kind
     OR NEW.media_type  IS DISTINCT FROM OLD.media_type
     OR NEW.byte_size   IS DISTINCT FROM OLD.byte_size
     OR NEW.sha256      IS DISTINCT FROM OLD.sha256
     OR NEW.received_at IS DISTINCT FROM OLD.received_at
     OR OLD.body IS NULL          -- already purged
     OR NEW.body IS NOT NULL      -- purge means NULL, nothing else
     OR NEW.body_purged_at IS NULL
  THEN
    RAISE EXCEPTION 'the only permitted update on audit.audit_artifact is a body purge'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END
$$;

-- Layer 3: blocks DDL against schema audit issued outside a migration session. Registered
-- on BOTH ddl_command_end and sql_drop (see db/post-migrate/01-audit-event-trigger.sql) —
-- pg_event_trigger_ddl_commands() does NOT return rows for DROP commands (verified live:
-- a DROP TABLE produced no rows from it and passed through unblocked); DROP requires the
-- separate sql_drop event and pg_event_trigger_dropped_objects(). A future Flyway
-- migration that legitimately needs to alter schema audit must set
-- fru.migration_in_progress = 'on' for its session before doing so (not wired up in
-- S2-01, since no such migration exists yet — see the S2-01 session report).
CREATE FUNCTION audit.block_audit_ddl() RETURNS event_trigger
LANGUAGE plpgsql AS $$
DECLARE
  r record;
BEGIN
  IF current_setting('fru.migration_in_progress', true) = 'on' THEN
    RETURN;
  END IF;
  IF TG_EVENT = 'sql_drop' THEN
    FOR r IN SELECT * FROM pg_event_trigger_dropped_objects() LOOP
      IF r.schema_name = 'audit' THEN
        RAISE EXCEPTION 'DDL against schema audit requires a migration session';
      END IF;
    END LOOP;
  ELSE
    FOR r IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
      IF r.schema_name = 'audit' THEN
        RAISE EXCEPTION 'DDL against schema audit requires a migration session';
      END IF;
    END LOOP;
  END IF;
END
$$;

-- Layer 4: the hash chain. SECURITY DEFINER, owned by fru_migrator (whoever runs this
-- migration — Flyway connects as fru_migrator, see application.properties), because the
-- chain-maintaining write to audit_chain.head_* requires privileges fru_app does not hold
-- (fru_app has SELECT only on audit_chain — see V0004). The application supplies NONE of
-- seq, occurred_at, prev_hash, content_hash or row_hash; any value it sends is overwritten.
CREATE FUNCTION audit.chain_append() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = audit, pg_catalog AS $$
DECLARE
  h      audit.audit_chain%ROWTYPE;
  a_hash bytea;
  canon  text;
BEGIN
  SELECT * INTO h FROM audit.audit_chain WHERE chain_id = NEW.chain_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown audit chain %', NEW.chain_id;
  END IF;

  NEW.seq         := h.head_seq + 1;
  NEW.occurred_at := clock_timestamp();
  NEW.prev_hash   := h.head_hash;

  a_hash := NULL;
  IF NEW.artifact_id IS NOT NULL THEN
    SELECT sha256 INTO a_hash FROM audit.audit_artifact WHERE artifact_id = NEW.artifact_id;
  END IF;

  canon := concat_ws(
    E'\x1e',
    NEW.chain_id::text,
    NEW.seq::text,
    to_char(NEW.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    NEW.event_type,
    NEW.actor_kind,
    coalesce(NEW.actor_id, ''),
    coalesce(NEW.profile_id::text, ''),
    coalesce(NEW.session_id::text, ''),
    coalesce(NEW.request_id::text, ''),
    coalesce(NEW.idempotency_key, ''),
    NEW.payload_json,
    coalesce(encode(a_hash, 'hex'), '')
  );

  NEW.content_hash := sha256(convert_to(canon, 'UTF8'));
  NEW.row_hash      := sha256(h.head_hash || NEW.content_hash);

  UPDATE audit.audit_chain
     SET head_seq = NEW.seq, head_hash = NEW.row_hash
   WHERE chain_id = NEW.chain_id;

  RETURN NEW;
END
$$;

-- Not in the AD-005 report's elided SQL (it states the genesis formula but not a function
-- computing it). Needed to make the hash chain self-consistently provable with pure SQL,
-- since no application/service code exists yet to compute genesis client-side — see the
-- S2-01 session report.
CREATE FUNCTION audit.chain_genesis() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.head_hash IS NULL THEN
    NEW.head_hash := sha256(
      convert_to(NEW.chain_id::text || '|' || NEW.chain_kind || '|' || coalesce(NEW.subject_id, ''), 'UTF8')
    );
  END IF;
  RETURN NEW;
END
$$;

-- Not in the AD-005 report (signature only, body elided as "…"). Walks a chain in seq
-- order, recomputes content_hash/row_hash from the stored columns exactly as
-- chain_append() would, checks seq contiguity, and compares the final hash to
-- audit_chain.head_hash.
CREATE FUNCTION audit.verify_chain(p_chain uuid)
RETURNS TABLE (ok boolean, checked bigint, first_bad_seq bigint, reason text)
LANGUAGE plpgsql AS $$
DECLARE
  chain_row       audit.audit_chain%ROWTYPE;
  rec             record;
  prev            bytea;
  a_hash          bytea;
  canon           text;
  computed_content bytea;
  computed_row    bytea;
  n               bigint := 0;
  expected_seq    bigint := 0;
BEGIN
  SELECT * INTO chain_row FROM audit.audit_chain WHERE chain_id = p_chain;
  IF NOT FOUND THEN
    RETURN QUERY SELECT false, 0::bigint, NULL::bigint, 'unknown chain'::text;
    RETURN;
  END IF;

  prev := sha256(
    convert_to(chain_row.chain_id::text || '|' || chain_row.chain_kind || '|' || coalesce(chain_row.subject_id, ''), 'UTF8')
  );

  FOR rec IN SELECT * FROM audit.audit_event WHERE chain_id = p_chain ORDER BY seq LOOP
    expected_seq := expected_seq + 1;
    n := n + 1;

    IF rec.seq <> expected_seq THEN
      RETURN QUERY SELECT false, n, rec.seq, format('sequence gap: expected %s got %s', expected_seq, rec.seq);
      RETURN;
    END IF;

    IF rec.prev_hash IS DISTINCT FROM prev THEN
      RETURN QUERY SELECT false, n, rec.seq, 'prev_hash mismatch'::text;
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
    IF computed_content IS DISTINCT FROM rec.content_hash THEN
      RETURN QUERY SELECT false, n, rec.seq, 'content_hash mismatch'::text;
      RETURN;
    END IF;

    computed_row := sha256(prev || computed_content);
    IF computed_row IS DISTINCT FROM rec.row_hash THEN
      RETURN QUERY SELECT false, n, rec.seq, 'row_hash mismatch'::text;
      RETURN;
    END IF;

    prev := computed_row;
  END LOOP;

  IF prev IS DISTINCT FROM chain_row.head_hash THEN
    RETURN QUERY SELECT false, n, NULL::bigint, 'final hash does not match chain head'::text;
    RETURN;
  END IF;

  RETURN QUERY SELECT true, n, NULL::bigint, 'ok'::text;
END
$$;
