-- app schema. Designed by the AD-005 report §6A (2026-08-22) and shaped to fit the
-- MessageSender port closed by AD-002c/S3-04 (docs/components/messaging.md). One row per
-- verified channel, inserted in the same transaction as a status update, its history row
-- and its audit event (AD-005 report §6.A) -- that insertion path is not built by this
-- migration; it belongs to whoever builds stage 1b/11 profile persistence. This migration
-- builds the table and the shape a dispatcher polls and writes back to.

CREATE TABLE app.notification_outbox (
  outbox_id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id           uuid        NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  channel               text        NOT NULL CHECK (channel IN ('sms','whatsapp','email')),
  destination           text        NOT NULL,
  payload               jsonb       NOT NULL,
  state                 text        NOT NULL DEFAULT 'pending'
                                     CHECK (state IN ('pending','dispatched','failed')),
  attempt_count         int         NOT NULL DEFAULT 0,
  next_attempt_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),

  -- Below: one-to-one with com.sfbank.bayanati.messaging.domain.MessageDispatchResult's
  -- fields, deliberately, so a dispatcher can write a result back with a plain parameterised
  -- UPDATE and no translation layer. NULL until the first dispatch attempt.
  outcome                text CHECK (outcome IN
                                      ('ACCEPTED','REJECTED','TRANSIENT_FAILURE','PERMANENT_FAILURE')),
  provider_id            text,
  provider_message_id    text,
  provider_status_code   text,
  provider_status_text   text,
  billed_segments        int,
  attempted_at           timestamptz,
  latency_millis         bigint
);

-- channel's value set is byte-identical to app.profile_channel.channel (V0007) and
-- app.otp_challenge.channel (V0021) -- the same three lowercase tokens, on purpose: an
-- OutboundMessage built from a claimed row carries this value straight through to
-- MessageChannel without a lookup or a translation.

-- The dispatcher's claim query (AD-005 report §6):
--   SELECT * FROM app.notification_outbox
--    WHERE state = 'pending' AND next_attempt_at <= clock_timestamp()
--    ORDER BY next_attempt_at
--    FOR UPDATE SKIP LOCKED LIMIT 50;
-- indexed here so that query does not degenerate into a sequential scan as the table grows.
CREATE INDEX notification_outbox_pending_idx ON app.notification_outbox (state, next_attempt_at);

-- No DELETE grant, matching app.profile_status_history's reasoning (V0010): a dispatch
-- attempt is history worth keeping, not a disposable queue entry, and nothing in the
-- design calls for removing a row once it lands. No append-only trigger is added, though
-- (unlike profile_status_history) -- unlike a status transition, one outbox row IS meant to
-- be mutated in place across retries (state, attempt_count, next_attempt_at, and the
-- result columns all change as the dispatcher works it), so an immutability trigger here
-- would break the design AD-005 itself specifies.
GRANT SELECT, INSERT, UPDATE ON app.notification_outbox TO fru_app;
