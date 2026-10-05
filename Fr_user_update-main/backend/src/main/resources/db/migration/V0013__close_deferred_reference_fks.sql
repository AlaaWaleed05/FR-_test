-- Closes the two FK constraints deferred in S2-02, now that ref.reference_item exists
-- (V0011). Both app.profile_customer_data and app.profile_status_history are empty in a
-- fresh dev/CI database at this point, so neither ALTER can fail on existing data.

-- Deferred at V0006 (app.profile_customer_data), exact statement from that file's comment:
ALTER TABLE app.profile_customer_data ADD CONSTRAINT occupation_fk
  FOREIGN KEY (occupation_list, occupation_version, occupation_code)
  REFERENCES ref.reference_item (list_code, version, item_code);

-- Deferred at V0009 (app.profile_status_history), exact statement from that file's comment:
ALTER TABLE app.profile_status_history ADD CONSTRAINT reason_code_fk
  FOREIGN KEY (reason_list, reason_version, reason_code)
  REFERENCES ref.reference_item (list_code, version, item_code);

-- Both FKs above default to MATCH SIMPLE (PostgreSQL's only option here — MATCH FULL would
-- reject the legitimate all-NULL case, but *_list is a GENERATED ALWAYS AS (constant)
-- column that is never NULL, so MATCH FULL cannot apply anyway). Under MATCH SIMPLE, a row
-- with the code column set but its paired version column NULL satisfies the FK trivially
-- (any NULL column skips the check), silently bypassing validation of a real code against
-- an unpinned version. Found in review; closed with an explicit pairing CHECK on each site,
-- beyond the two ALTER statements S2-02 pre-wrote as comments -- the same kind of
-- role-separation-style extension S2-02's own V0010 made to the app schema.
ALTER TABLE app.profile_customer_data ADD CONSTRAINT occupation_code_version_paired
  CHECK ((occupation_code IS NULL) = (occupation_version IS NULL));

ALTER TABLE app.profile_status_history ADD CONSTRAINT reason_code_version_paired
  CHECK ((reason_code IS NULL) = (reason_version IS NULL));
