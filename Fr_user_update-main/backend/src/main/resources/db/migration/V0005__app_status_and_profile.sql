-- app schema, per AD-005 report §4.1. Closed code sets that need labels get a lookup table
-- (not a PG ENUM: ALTER TYPE ADD VALUE interacts badly with Flyway's all-migrations-in-a-
-- transaction behaviour on PostgreSQL — see docs/components/persistence.md "Do NOT use").

CREATE TABLE app.status_code (
  code        text PRIMARY KEY,
  label_ar    text NOT NULL,
  label_en    text NOT NULL,
  is_terminal boolean NOT NULL
);

-- is_terminal follows operator.md's own grouping verbatim: "In flight" vs "Terminal for the
-- customer" (docs/journeys/operator.md, "Profile statuses"). "Terminal for the customer" does
-- NOT mean the row can never change again (rejected -> approved is a legitimate later
-- transition, operator.md "Re-approving a rejected profile") — it means the customer's own
-- journey has ended for that status. Labels are seed placeholders, not final UX copy.
INSERT INTO app.status_code (code, label_ar, label_en, is_terminal) VALUES
  ('in_progress',                 'قيد التنفيذ',                         'In progress',                      false),
  ('awaiting_registry',           'بانتظار السجل المدني',                'Awaiting Civil Registry',          false),
  ('blocked_scan',                'محظور مؤقتًا - المسح الضوئي',         'Blocked - document scan',          false),
  ('blocked_liveness',            'محظور مؤقتًا - التحقق الحي',          'Blocked - liveness',               false),
  ('abandoned',                   'متروك',                               'Abandoned',                        false),
  ('submitted',                   'مُقدَّم',                              'Submitted',                        true),
  ('approved',                    'معتمد',                               'Approved',                         true),
  ('rejected',                    'مرفوض',                               'Rejected',                         true),
  ('terminated_registry_mismatch','منتهي - تعارض مع السجل المدني',      'Terminated - registry mismatch',   true);

CREATE TABLE app.profile (
  profile_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  reference_number  text        UNIQUE,               -- NULL until submitted (stage 11)
  branch_code       text        NOT NULL,
  account_number    text        NOT NULL,
  status            text        NOT NULL REFERENCES app.status_code(code),
  status_changed_at timestamptz NOT NULL,
  provenance        text        NOT NULL DEFAULT 'digital'
                                CHECK (provenance IN ('digital','manual')),
  resume_stage      smallint    NOT NULL DEFAULT 1,   -- last completed journey stage
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  last_activity_at  timestamptz NOT NULL,             -- drives 30d abandoned, 90d purge
  submitted_at      timestamptz,
  pii_purged_at     timestamptz,                      -- 90-day retention rule; row survives
  row_version       bigint      NOT NULL DEFAULT 1,
  CONSTRAINT profile_one_per_account UNIQUE (branch_code, account_number)
);
-- UNIQUE (branch_code, account_number) is the database-level expression of the campaign rule
-- "one update per account" (customer.md stage 1a). The profile row is never deleted — the
-- 90-day retention rule nulls PII and sets pii_purged_at, so the reference number and the
-- audit linkage survive.
