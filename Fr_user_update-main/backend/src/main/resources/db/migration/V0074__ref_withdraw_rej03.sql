-- BL-154: REJ-03 «فشل أو عدم وضوح مطابقة الوجه» is withdrawn. Product-owner ruling, 2026-09-16.
--
-- AD-022 ruling 3 stopped DISPLAYING face-match results. It did not disposition the rejection
-- reason that cites them, so an operator was left able to reject a customer for a face-match
-- failure they can no longer see -- a coded reason with no on-screen evidence behind it, which the
-- dashboard then aggregates as though it were observed. The ruling was: withdraw it.
--
-- WITHDRAWN BY is_active, NOT BY A NEW LIST VERSION, and the difference matters.
--
--   * DELETE is impossible. app.profile_status_history carries a composite FK onto
--     ref.reference_item (V0013), so any profile already rejected under REJ-03 pins the row.
--   * A new `rejection_reason` version 2 omitting REJ-03 would work for the FK -- version 1 stays
--     intact -- but ProfileListPage resolves labels and the reason FILTER against the CURRENT
--     version only. Publishing a version 2 would therefore make every profile already rejected
--     under REJ-03 unfilterable by reason, and show a bare code where a label used to be. That is
--     a worse outcome than the problem being fixed.
--   * is_active = false achieves the withdrawal exactly where it is needed and nowhere else:
--     JdbcReferenceCatalog.EXISTS filters `AND is_active = true`, so OperatorReviewService's own
--     validateReasonCode now refuses REJ-03 with a 400. Meanwhile JdbcReferenceCatalog.FIND does
--     NOT filter it, and neither do JdbcProfileViewRepository's nor JdbcProfileListRepository's
--     history joins -- so every historical REJ-03 rejection keeps its Arabic label, its English
--     label and its place in the filter.
--
-- The back office must ALSO filter it out of the picker, because ReferenceDocumentPublisher emits
-- inactive items (it selects is_active with no WHERE clause, deliberately -- a client needs the row
-- to resolve a label for a historical value). Without that client-side filter an operator would
-- still see REJ-03 in the reject dropdown and get a 400 on submit. That half ships in the same
-- session, in backoffice/src/profiles/RejectModal.tsx.
--
-- content_hash is NOT recomputed here and does not need to be. V0019's canonicalisation is
-- item_code|parent_code|label_ar|label_en, which does not include is_active, and
-- ReferenceDocumentPublisher.UPDATE_CONTENT_HASH is the live authority in any case -- it rewrites
-- the hash at publication on every startup. item_count is likewise untouched: the row still
-- exists, it is merely not offerable. (R-033 already records that `extra` sits outside the
-- integrity envelope for the same reason; is_active is a third such field. No mobile client
-- consumes `rejection_reason`, so nothing caches a stale copy of it.)
--
-- Not a data migration and not reversible by a later seed: this is a statement about which reasons
-- an operator may CHOOSE, and reversing it means another product-owner ruling and another
-- migration.
UPDATE ref.reference_item
   SET is_active = false
 WHERE list_code = 'rejection_reason'
   AND item_code = 'REJ-03';

COMMENT ON COLUMN ref.reference_item.is_active IS
  'false hides a row from every picker and from ReferenceCatalog.exists(), while leaving it resolvable by ReferenceCatalog.find() and by the status-history label joins -- which is what lets a withdrawn code keep explaining the profiles already recorded under it. Used for the superseded occupation duplicates (133/139/135, V0014) and, since V0074, for the withdrawn rejection reason REJ-03 (BL-154).';
