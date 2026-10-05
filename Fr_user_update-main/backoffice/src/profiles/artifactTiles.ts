import type { ArtifactRefView } from '../api/types';
import { documentTypeLabel } from './detailLabels';

/**
 * The contact sheet's tile set — wayfinder ticket 02's Variant B, in render order.
 *
 * MIRRORS `operator.domain.OperatorImagePolicy.viewableKinds()`, which is the source of truth: the
 * endpoint 404s anything outside it, so a kind listed here but not there renders a broken image,
 * and a kind there but not here is simply invisible. **The two lists are copies and NO GATE
 * DETECTS DRIFT** — there is no cross-tier check in either direction, and the Java side's own
 * javadoc ("the two would drift the first time a kind is added") was written before this file
 * existed. Whoever adds a kind server-side must add it here
 * in the same commit or it stays invisible with every gate green. That is not hypothetical: it is
 * exactly what happened at S8-24, when `salary_certificate` was admitted on both sides at once.
 *
 * The three kinds the profile-detail listing ALSO returns and this list deliberately omits:
 *
 * - `doc_front_frame` / `doc_back_frame` — AD-004 stores no bytes for them, yet they declare a
 *   `content_type` and a `byte_size` (one measured live at 1,726,304 bytes with a NULL body). They
 *   are the LARGEST rows in the listing, so linking every listed row would put a guaranteed broken
 *   image on the most prominent one. That is exactly what BL-075 requirement (a) exists to prevent.
 * - `doc_back` — ticket 02's explicit decision ("the set is the set").
 *
 * `salary_certificate` WAS a third omission and is now the sixth tile — BL-136, closed at S8-24 by
 * product-owner decision. Ticket 02's own premise counted six byte-carrying kinds when there are
 * seven, so the seventh was never weighed; and once S8-23 deleted the metadata attachments table,
 * refusing it left the one document bearing on income invisible in every surface the system has. It
 * is last in render order deliberately: identity evidence first, income evidence after.
 *
 * The listing is not filtered server-side — `JdbcProfileViewRepository`'s `ARTIFACTS` query filters
 * on ownership and `state = 'committed'` only — so this filter is the whole control client-side.
 */
/**
 * REORDERED AT S9-02 to the approved artboard's own sequence (product-owner ruling, 2026-09-16):
 * the two portraits first, so the face an operator is comparing is the first thing on the row,
 * then the document, then the liveness capture, then the two things the customer supplied. The
 * previous order put `doc_front` first; BL-136's "identity evidence first, income evidence last"
 * still holds, and the salary certificate is still last.
 *
 * The SET is unchanged. `doc_back` is still absent -- ticket 02's "the set is the set", reaffirmed
 * as BL-159 when the artboard drew a seventh tile for it. Six, not seven.
 */
export const VIEWABLE_KINDS = [
  'portrait_registry',
  'portrait_uqudo',
  'doc_front',
  'face_audit_trail',
  'signature',
  'salary_certificate',
] as const;

export type ViewableKind = (typeof VIEWABLE_KINDS)[number];

/**
 * The tile caption is the image NAME, not metadata (ticket 02's first correction to Variant B):
 * MIME type, byte size and checksum are off the tile, because an operator reviewing a face does
 * not read a checksum.
 *
 * ARABIC, decided 2026-09-13 (S8-23) after both earlier tickets shipped English captions and both
 * flagged that the language had never been put to the product owner. **REWORDED AT S9-02** to the
 * approved artboard's own strings.
 *
 * Note that `portrait_registry` and `portrait_uqudo` now share the caption «الصورة الشخصية». That
 * is the artboard's doing and it is not a mistake: they are the same THING from two sources, and
 * {@link TILE_SOURCES_AR} is what tells them apart. Anywhere the caption becomes an accessible
 * name, the two lines must be combined -- see {@link tileAccessibleName} -- or a screen reader
 * announces two identical tiles.
 */
export const TILE_CAPTIONS_AR: Record<ViewableKind, string> = {
  portrait_registry: 'الصورة الشخصية',
  portrait_uqudo: 'الصورة الشخصية',
  doc_front: 'وجه وثيقة الهوية',
  face_audit_trail: 'صورة التحقق الحي',
  signature: 'التوقيع',
  salary_certificate: 'شهادة المرتب',
};

/**
 * The artboard's SECOND caption line: where this image came from.
 *
 * New at S9-02. It is the line that makes two «الصورة الشخصية» tiles legible, and it is the only
 * place on the screen that says a document image came off a national ID rather than a passport.
 *
 * **Two of the six depend on the document actually scanned**, which is why this is a function and
 * not a second `Record`. The artboard's fixture is a national-ID customer and draws
 * «البطاقة القومية» under both document tiles; a passport customer must read «جواز سفر» there, and
 * hardcoding the artboard's string would have mislabelled every passport profile. `documentType`
 * is `scanResult.documentType`, null before the scan lands.
 */
export function TILE_SOURCES_AR(kind: ViewableKind, documentType: string | null): string {
  switch (kind) {
    case 'portrait_registry':
      return 'السجل المدني';
    case 'portrait_uqudo':
    case 'doc_front':
      // documentTypeLabel returns '—' for a null type, which is honest here: before the scan
      // lands there is no document to name, and the tile is empty anyway.
      return documentTypeLabel(documentType);
    case 'face_audit_trail':
      return 'التحقق الحي';
    case 'signature':
      return 'وقّعه العميل';
    case 'salary_certificate':
      return 'أرفقها العميل';
  }
}

/**
 * Caption plus source, for anywhere one string must identify a tile on its own — an `alt`, an
 * `aria-label`, a preview title.
 *
 * Exists because {@link TILE_CAPTIONS_AR} is deliberately ambiguous for the two portraits. Using
 * the caption alone would give a screen-reader user two tiles both called «الصورة الشخصية» with
 * nothing to choose between them.
 */
export function tileAccessibleName(kind: ViewableKind, documentType: string | null): string {
  return `${TILE_CAPTIONS_AR[kind]} — ${TILE_SOURCES_AR(kind, documentType)}`;
}

/**
 * Whether this artifact must be opened rather than rendered in an `<img>`.
 *
 * Only `salary_certificate` can be a PDF: the customer picks the file and
 * `salary_certificate_field.dart` offers `jpg`, `jpeg`, `png` and `pdf`, storing a picked PDF
 * byte-identical. The endpoint's type allow-list is scoped per kind for the same reason, so a
 * `doc_front` claiming `application/pdf` is refused server-side however this reads it here.
 *
 * GATED ON KIND as well as type, mirroring the server's per-kind allow-list rather than trusting
 * the column alone. `contentType` is DECLARED by whoever stored the row and can lie — it is the same
 * column the civil-registry stub uses to call a 32-byte ASCII string a JPEG. Without the kind test,
 * a `doc_front` whose column claimed `application/pdf` would render a document tile where the
 * passport should be and then frame a URL the server 404s, leaving the operator looking at a blank
 * frame with nothing saying anything went wrong. With it, every non-certificate kind falls through
 * to the `<img>` path, where a failure hits `onError` and becomes a visible broken tile.
 *
 * The remaining mismatch is a certificate that lies about ITSELF, and it degrades either way: a PDF
 * declared `image/jpeg` breaks its `<img>` into the broken tile, and JPEG bytes declared
 * `application/pdf` are served as a PDF and fail in the browser's own viewer. Neither is silent, and
 * neither is worth sniffing bytes to pre-empt — the response carries `X-Content-Type-Options:
 * nosniff` precisely so the declared type is the only thing that decides.
 */
export function isPdf(artifact: ArtifactRefView): boolean {
  return (
    artifact.kind === 'salary_certificate' &&
    artifact.contentType.split(';', 1)[0].trim().toLowerCase() === 'application/pdf'
  );
}

/**
 * The `<img src>` for one artifact's bytes: `operator.web.ProfileImageController`, a same-origin
 * cookie-authenticated GET meant to go straight into an `<img>` (R-046 is CLOSED — no signed URL,
 * no blob fetch). Root-relative, sharing `http.ts`'s `/api/v1` prefix.
 *
 * Deliberately NOT routed through `apiFetch`: the browser must issue this itself so the response
 * lands in the `<img>`, and the endpoint's `Cache-Control: no-store, private` keeps the browser from
 * serving a later FETCH out of its own cache — which is what keeps the `profile_image_viewed` audit
 * event honest. (It does not make every RENDER a request: a re-render of a mounted `<img>` whose
 * `src` has not changed issues nothing, and no event is emitted for it. The event counts fetches,
 * not eyeballs — `OperatorImageService` says so on its own side.)
 *
 * Ticket 01 binds the artifact id to this src and OUT of the SPA's address bar, so it stays clear of
 * browser history and of any referrer. The enlarge modal is therefore component state, never a route.
 */
export function artifactImageSrc(profileId: string, artifactRefId: string): string {
  return `/api/v1/operator/profiles/${encodeURIComponent(profileId)}/artifacts/${encodeURIComponent(artifactRefId)}`;
}

/** One tile. `artifact` is null when this profile carries no committed row of that kind yet. */
export interface ArtifactTile {
  kind: ViewableKind;
  caption: string;
  artifact: ArtifactRefView | null;
}

/**
 * The six tiles, always all six and always in order, whatever the profile carries.
 *
 * LAST match wins, not first. `app.artifact_ref` is `UNIQUE (cycle_id, kind)`, so a profile with
 * several identity cycles holds several committed rows of the same kind, and the listing is
 * `ORDER BY ar.created_at` ascending — the FIRST row of a kind is therefore the OLDEST, i.e. the
 * superseded scan rather than the one that replaced it. Picking the first (as this page's old
 * two-portrait `find` did) would show an operator a stale passport photograph and give them no way
 * to tell. Nothing else in the response distinguishes them: `ArtifactRefView` carries no timestamp,
 * so the listing's order is the only signal there is.
 *
 * A kind with no row keeps its tile and renders an empty state — the operator needs to know the
 * liveness capture is missing, not merely not see it.
 *
 * KNOWN RESIDUAL, undetectable from this tier: each kind is resolved independently, so a profile
 * whose newer identity cycle committed some kinds and not others shows a MIXED set — a `doc_front`
 * from cycle 2 beside a `portrait_uqudo` from cycle 1 — with nothing on screen saying so.
 * `ArtifactRefView` carries neither `cycleId` nor a timestamp, so the client cannot group by cycle
 * even to warn. Strictly better than the `find()` this replaced, which took the oldest of every
 * kind; fixing it properly needs a wider response.
 */
export function selectTiles(artifacts: ArtifactRefView[]): ArtifactTile[] {
  return VIEWABLE_KINDS.map((kind) => {
    const matches = artifacts.filter((a) => a.kind === kind);
    return {
      kind,
      caption: TILE_CAPTIONS_AR[kind],
      artifact: matches.length > 0 ? matches[matches.length - 1] : null,
    };
  });
}
