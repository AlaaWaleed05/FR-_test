-- Stage 10 (S3-13) needs the portrait image bytes (documents[0].scan.faceImageId, per
-- uqudo-sdk.md's "Face matching -- how it actually works") to create a Uqudo Face Session
-- via POST /api/v1/face. S3-12's submitScan downloads every image, checksums it, and
-- discards ALL bytes once verified (only a sha256 hash and a synthetic storage key
-- survive, AD-004 being unresolved), and purges Uqudo's own session right after the scan
-- is accepted -- well before the customer reaches stage 10, since stage 9's Civil Registry
-- review sits in between and can pause on awaiting_registry indefinitely. By stage 10 the
-- portrait is gone from both Uqudo and this backend.
--
-- This column is a narrow, transient bridge for exactly that gap: the portrait bytes
-- IdentityScanService.submitScan already has in hand at accept time, stashed here instead
-- of discarded, so LivenessService can re-upload them to Uqudo fresh on every liveness
-- attempt (a new Face Session is required each attempt regardless -- Uqudo deletes the
-- session and its image after 600s). It is NOT AD-004's decision: AD-004 is the long-term
-- artifact-store technology for all nine artifact kinds (object storage + references,
-- direction established, not settled); this column holds raw bytes directly in the profile
-- database and exists only until one of three clearing paths fires -- see V0042.
--
-- This is also a PII surface AD-004's design does not cover, since AD-004 was never scoped
-- to consider a column like this one. Filed as its own risk, not only this comment -- see
-- RISKS.md.
ALTER TABLE app.scan_result
  ADD COLUMN face_reference_image bytea;

-- No new grant: app.scan_result already carries SELECT, INSERT, UPDATE for fru_app (V0010),
-- which covers new columns on the same table.
