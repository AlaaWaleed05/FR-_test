import { useState } from 'react';
import { Image, Modal } from 'antd';
import type { ArtifactRefView, SalaryCertificateState } from '../api/types';
import { INK, PALETTE } from '../theme/palette';
import { TILE_SOURCES_AR, artifactImageSrc, isPdf, selectTiles, tileAccessibleName } from './artifactTiles';

/**
 * Wayfinder ticket 02's Variant B — the flat contact sheet, and the half of BL-075 that makes an
 * operator actually see an identity image.
 *
 * A uniform grid of equal tiles: no privileged pair, no attachments table, each tile clickable into
 * a pop-up (the S7-12 product-owner request). Rejected variants were A ("comparison first", a hero
 * portrait pair with everything else demoted) and C ("stage and rail").
 *
 * Five tiles are identity images and open in the shared `Image.PreviewGroup`. The sixth,
 * `salary_certificate` (BL-136, admitted at S8-24), may be a PDF, which no `<img>` can render — so a
 * PDF certificate gets a document tile and opens in its own modal. Deliberately not a new tab:
 * ticket 01 binds the artifact id out of browser history and out of any referrer, and a new tab
 * puts it in both. Deliberately not a download either — that would leave an unencrypted copy of a
 * customer's pay document in an operator's downloads folder.
 *
 * **RESTYLED AT S9-02** to `Design_3/backoffice/approved/profile-screen.dc.html`'s section 2: the
 * approved AZ palette instead of the old navy tokens, six columns instead of `auto-fill`, 104px
 * media instead of 190px, and a SECOND caption line naming each image's source. Square corners and
 * no shadows survive from the old tokens because the approved artboard draws them the same way.
 *
 * **antd's `Image`/`Modal` are KEPT here, and that is not a hole in "antd goes for these two
 * screens".** What the artboard draws for these tiles are placeholder SVG icons; what the screen
 * must actually do is render real bytes with a click-to-enlarge that pages between them. That
 * enlarge behaviour is `Image.PreviewGroup`, and reimplementing a lightbox by hand would be a
 * large body of new untested code in exchange for nothing an operator can see. The antd that AD-021
 * displaces is the CHROME -- Descriptions, Card, Layout, Divider, Tag -- not a behaviour with no
 * bare-HTML equivalent.
 */

const RULE = INK.BORDER;
const WELL = INK.WELL;
const NAVY = PALETTE.TEXT;
const MUTED = INK.MUTED;

/** The artboard's tile media height. Zoom is the preview, not the tile. */
const TILE_MEDIA_HEIGHT = 104;

/**
 * What the salary-certificate tile says when the bank holds no certificate (BL-122).
 *
 * `PRESENT` is here for exhaustiveness only — a present certificate renders as a real tile and never
 * reaches `EmptyTile` — but naming it keeps this map total, so adding a state to
 * `SalaryCertificateState` is a TypeScript error here rather than an `undefined` caption in front of
 * an operator.
 *
 * `ATTACH_FAILED` names the customer as the actor and the bank as the place the file did not reach,
 * because the operator's question is "did this person try?", and the answer is yes.
 */
const SALARY_CERTIFICATE_ABSENCE: Record<SalaryCertificateState, string> = {
  PRESENT: 'لم يُرفق (اختياري)',
  DECLINED: 'لم يُرفق (اختياري)',
  ATTACH_FAILED: 'أرفق العميل شهادة لم تصل إلى البنك',
  NOT_REACHED: 'لم يصل الملف إلى هذه المرحلة بعد',
};

interface Props {
  profileId: string;
  artifacts: ArtifactRefView[];
  salaryCertificateState: SalaryCertificateState;
  /**
   * `scanResult.documentType`, null until the scan lands.
   *
   * New at S9-02 and the reason this component gained a prop at all: the artboard's second
   * caption line names the source, and for the two document-derived tiles that source IS the
   * document scanned. Without it every profile would be captioned «البطاقة القومية», mislabelling
   * every passport customer on the one screen that says which document the bank holds.
   */
  documentType: string | null;
}

/**
 * A tile whose bytes did not load. Deliberately NOT a silhouette and NOT the browser's broken-image
 * glyph: an empty box tells an operator nothing, and BL-075 exists because an operator reviewed a
 * profile without knowing what they had not seen.
 *
 * Two causes are expected in practice and neither is a bug here. `portrait_registry` is a 32-byte
 * ASCII string under `content_type: image/jpeg` whenever `fru.civil-registry.client=stub`, so it
 * renders broken by design in any stubbed environment. And an expired session 401s every tile
 * without the SPA noticing — `setUnauthorizedHandler` fires inside `apiFetch` only, and an `<img>`
 * never goes through it — so every tile failing at once is what a dropped session looks like.
 */
function BrokenTile({ caption, source }: { caption: string; source: string }): React.JSX.Element {
  return (
    <div>
      <div
        style={{
          height: TILE_MEDIA_HEIGHT,
          background: '#2a1215',
          border: '1px dashed #9c2f2f',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 6,
          padding: 8,
          textAlign: 'center',
        }}
      >
        <span style={{ color: '#ff7875', fontSize: 12, fontWeight: 600 }}>تعذّر عرض هذه الصورة</span>
        <span style={{ color: '#ffccc7', fontSize: 11 }}>قد تكون الجلسة منتهية أو الملف غير صالح</span>
      </div>
      <TileCaption caption={caption} source={source} />
    </div>
  );
}

/**
 * A kind this profile carries no committed row of.
 *
 * The wording differs for the salary certificate, and the difference matters to a reviewing
 * operator. The five identity artifacts are produced by stages the journey requires, so their
 * absence really does mean the customer has not got that far yet. The certificate is OPTIONAL
 * («اختياري» on the customer's own screen; `SalaryCertificateService` gates nothing on it and never
 * refuses a submission for its absence), so its absence is usually a finished state, not a pending
 * one. Telling an operator that a submitted profile "has not reached this stage yet" would be a
 * claim about the future that is never going to come true.
 *
 * **BL-122, closed.** Until 2026-09-13 this tile could not distinguish declined from upload-failed —
 * both leave no row at all — and said so. The backend now resolves the difference and sends it as
 * `salaryCertificateState`, so the three absences read differently here.
 *
 * The copy states a fact and asks for nothing. Product-owner ruling, 2026-09-13: an operator does
 * nothing differently about a certificate that never arrived. It stays optional, it still gates
 * nothing, and `ATTACH_FAILED` is not a rejection reason — hence no warning colour, no icon and no
 * call to action on any of these, which would all imply an operator decision that does not exist.
 */
function EmptyTile({
  caption,
  source,
  kind,
  salaryCertificateState,
}: {
  caption: string;
  source: string;
  kind: string;
  salaryCertificateState: SalaryCertificateState;
}): React.JSX.Element {
  const message =
    kind === 'salary_certificate'
      ? SALARY_CERTIFICATE_ABSENCE[salaryCertificateState]
      : 'لم يصل الملف إلى هذه المرحلة بعد';
  return (
    <div>
      <div
        style={{
          height: TILE_MEDIA_HEIGHT,
          background: WELL,
          border: `1px solid ${RULE}`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 8,
          textAlign: 'center',
        }}
      >
        <span style={{ color: MUTED, fontSize: 12 }}>{message}</span>
      </div>
      <TileCaption caption={caption} source={source} />
    </div>
  );
}

/**
 * A tile for an artifact that cannot go in an `<img>` — today, only a PDF salary certificate.
 *
 * It carries no `<img>` at all rather than a placeholder image, so a count of images on the page
 * stays a count of things actually being fetched as images, and it sits OUTSIDE the shared
 * `Image.PreviewGroup`: dropping a non-image into that group would put an entry in the preview's
 * arrow sequence that the preview cannot render.
 */
function DocumentTile({
  caption,
  source,
  name,
  onOpen,
}: {
  caption: string;
  source: string;
  /** Caption AND source -- see `tileAccessibleName`. */
  name: string;
  onOpen: () => void;
}): React.JSX.Element {
  return (
    <div>
      <button
        type="button"
        onClick={onOpen}
        aria-label={name}
        style={{
          height: TILE_MEDIA_HEIGHT,
          width: '100%',
          background: WELL,
          border: `1px solid ${RULE}`,
          borderRadius: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 8,
          cursor: 'pointer',
          font: 'inherit',
          color: NAVY,
          padding: 8,
        }}
      >
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke={PALETTE.PURPLE} strokeWidth="1.2">
          <path d="M6 2.5h8l4 4v15H6z" />
          <path d="M14 2.5v4h4" />
          <path d="M8.5 12h7M8.5 15h7M8.5 18h4" />
        </svg>
        <span style={{ fontSize: 12, color: MUTED }}>PDF — انقر للعرض</span>
      </button>
      <TileCaption caption={caption} source={source} />
    </div>
  );
}

/**
 * The artboard's two caption lines under every tile: the image's name, then its source in a
 * smaller muted line. Shared by all four tile kinds so an empty tile is captioned exactly like a
 * present one -- an operator scanning the row should see the same six labels whatever the profile
 * happens to carry.
 */
function TileCaption({ caption, source }: { caption: string; source: string }): React.JSX.Element {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 6 }}>
      <span style={{ fontSize: 12, fontWeight: 500, color: NAVY }}>{caption}</span>
      <span style={{ fontSize: 11, color: MUTED }}>{source}</span>
    </div>
  );
}

export default function ArtifactContactSheet({
  profileId,
  artifacts,
  salaryCertificateState,
  documentType,
}: Props): React.JSX.Element {
  // Keyed by artifact id rather than by kind. Behaviourally identical today, since `selectTiles`
  // yields at most one artifact per kind -- but a failure belongs to the row that failed, so the id
  // keeps meaning if this ever renders more than one row of a kind.
  const [failed, setFailed] = useState<Record<string, true>>({});
  // The open PDF, held as component state rather than a route: ticket 01 keeps the artifact id out
  // of the address bar, so it stays clear of browser history and of any referrer.
  const [openDocument, setOpenDocument] = useState<{ src: string; caption: string } | null>(null);

  const tiles = selectTiles(artifacts);

  return (
    /* No card, no «المرفقات» heading: at S9-02 this sheet became the top BAND of section 2 rather
       than a card of its own, and SectionCard supplies the border and the heading around it. */
    <div style={{ padding: '14px 16px', borderBottom: `1px solid ${INK.ROW_RULE}` }}>

      {/* One PreviewGroup over the sheet's IMAGE tiles, so the enlarged view pages between them with
          the arrows rather than closing and reopening. A PDF certificate is not among them — it has
          its own modal below, since the preview cannot render one. The artifact id stays in the
          `<img src>` and out of the address bar (ticket 01), so both are component state, never a
          route. */}
      <Image.PreviewGroup>
        {/* Six columns, the artboard's own count, but `auto-fit`/`minmax` rather than a literal
            `repeat(6, 1fr)` so the row wraps instead of squeezing six 104px tiles into a narrow
            window. The artboard is a fixed 1240px canvas and has no narrow case to draw. */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 150px), 1fr))', gap: 12 }}>
          {tiles.map(({ kind, caption, artifact }) => {
            const source = TILE_SOURCES_AR(kind, documentType);
            // Caption AND source. Two tiles share the caption «الصورة الشخصية», so the caption
            // alone is not a name -- it would announce two identical tiles.
            const name = tileAccessibleName(kind, documentType);
            if (!artifact)
              return (
                <EmptyTile
                  key={kind}
                  kind={kind}
                  caption={caption}
                  source={source}
                  salaryCertificateState={salaryCertificateState}
                />
              );
            if (failed[artifact.artifactRefId]) return <BrokenTile key={kind} caption={caption} source={source} />;
            if (isPdf(artifact)) {
              return (
                <DocumentTile
                  key={kind}
                  caption={caption}
                  source={source}
                  name={name}
                  onOpen={() =>
                    setOpenDocument({ src: artifactImageSrc(profileId, artifact.artifactRefId), caption: name })
                  }
                />
              );
            }
            return (
              <div key={kind}>
                <div
                  style={{
                    height: TILE_MEDIA_HEIGHT,
                    background: WELL,
                    border: `1px solid ${RULE}`,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    overflow: 'hidden',
                  }}
                >
                  <Image
                    src={artifactImageSrc(profileId, artifact.artifactRefId)}
                    alt={name}
                    height={TILE_MEDIA_HEIGHT}
                    style={{ objectFit: 'contain' }}
                    preview={{ mask: 'تكبير الصورة' }}
                    onError={() => setFailed((previous) => ({ ...previous, [artifact.artifactRefId]: true }))}
                  />
                </div>
                <TileCaption caption={caption} source={source} />
              </div>
            );
          })}
        </div>
      </Image.PreviewGroup>

      {/* The PDF's own enlarge modal. An <iframe> hands the bytes to the browser's sandboxed PDF
          viewer, and its src never becomes a top-level history entry the way a new tab would. */}
      {/* The MODAL ITSELF is conditional, not just its children. Two reasons, and the second is the
          load-bearing one. `rc-dialog` wraps a closed modal's children in `MemoChildren` and stops
          re-rendering them, and `destroyOnHidden` only takes effect once the leave MOTION ends --
          so a `<Modal open={false}>` holding a conditional child can keep that child mounted, and
          here the child is an iframe holding a customer's pay document. Unmounting the whole modal
          leaves nothing to reason about: React drops the subtree the moment the state clears. */}
      {openDocument && (
        <Modal
          open
          title={openDocument.caption}
          onCancel={() => setOpenDocument(null)}
          footer={null}
          width="80vw"
          styles={{ body: { padding: 0 } }}
        >
          <iframe
            src={openDocument.src}
            title={openDocument.caption}
            style={{ width: '100%', height: '75vh', border: 'none', display: 'block' }}
          />
        </Modal>
      )}
    </div>
  );
}
