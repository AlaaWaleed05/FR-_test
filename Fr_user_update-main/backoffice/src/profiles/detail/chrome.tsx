import type { ReactNode } from 'react';
import BrandHeader from '../../layout/BrandHeader';
import { ARABIC_FONT, INK, NUMERIC_FONT, PALETTE } from '../../theme/palette';

/**
 * The furniture of the approved profile screen — `Design_3/backoffice/approved/profile-screen.dc.html`.
 *
 * Extracted from `ProfileDetailPage` so the page reads as three sections and an action bar rather
 * than as six hundred lines of inline style. Everything here is layout: the one exception, the
 * header's sign-out, moved to `layout/BrandHeader` at S9-06 when the shell took the same header.
 *
 * **antd is deliberately absent from this file.** It is part of the profile screen, which AD-021
 * rebuilds to a bare inline-styled artboard; `RejectModal` and `PrintFormModal` are separate
 * components and keep antd.
 */

/**
 * Latin digits, dates and identifiers.
 *
 * `<bdi>` isolates the content's own bidi direction from the surrounding RTL paragraph. Without
 * it a leading `+` or an all-digit/punctuation string — an E.164 phone number, a reference
 * number, a date — has no strong-direction character to anchor it and the browser's bidi
 * algorithm visually reorders it: «+249912340001» renders as «249912340001+», with the plus
 * pushed to the wrong end of the number.
 *
 * **`dir="ltr"` EXPLICITLY, not `<bdi>`'s default `auto`.** `auto` means "take the direction of
 * the first STRONG character", and a phone number, an account number and a date contain none —
 * `+`, the digits and the separators are all neutral or weak. The algorithm then falls back to
 * LTR, which gives the right answer today by luck rather than by instruction: the moment a value
 * routed through here begins with an Arabic character (a branch name, an Arabic-Indic digit) the
 * whole span flips and the plus lands at the wrong end again. Everything this component wraps is
 * Latin-script or numeric BY CONSTRUCTION, so saying so is both safe and the honest spelling of
 * the intent.
 *
 * Arabic text needs no isolation and must not get this treatment — it is not routed here.
 *
 * `tabular-nums` is the part of the artboard's condensed face that actually matters: it makes
 * columns of numbers line up. See `theme/palette.ts` on why the face itself is not loaded.
 */
export function Num({ children }: { children: ReactNode }): React.JSX.Element {
  return (
    <span style={{ fontFamily: NUMERIC_FONT, fontVariantNumeric: 'tabular-nums' }}>
      <bdi dir="ltr">{children}</bdi>
    </span>
  );
}

/**
 * The screen header — now {@link BrandHeader}, shared with `AppShell`.
 *
 * It was duplicated here until S9-06, on the reasoning that the shell was on its way out of this
 * screen's life entirely. That is still true of the LAYOUT — the profile screen sits OUTSIDE
 * `AppShell` by product-owner ruling, 2026-09-16, and keeps no sider — but it stopped being the
 * right conclusion for the HEADER the moment the product owner asked for the same header on the
 * list screen. Kept as a named wrapper rather than deleted so the page still reads as its three
 * artboard sections, and so this note has somewhere to live.
 *
 * The queue position the artboard draws («الملف ٣ من ١٠ في قائمة المراجعة») is NOT rendered —
 * BL-158. No backend concept exists for a profile's ordinal within a filtered, sorted result
 * set, and carrying it as router state would be wrong the moment the profile is opened directly.
 */
export function ProfileHeader(): React.JSX.Element {
  return <BrandHeader />;
}

/** One of the header card's small tinted chips — account number, branch, provenance, status. */
export function Badge({
  children,
  background,
  color,
}: {
  children: ReactNode;
  background?: string;
  color?: string;
}): React.JSX.Element {
  return (
    <span
      style={{
        fontSize: 12,
        padding: '4px 10px',
        background: background ?? INK.TINT,
        color: color ?? PALETTE.TEXT,
      }}
    >
      {children}
    </span>
  );
}

/**
 * The identity card under the header: reference number, customer name, and the four badges.
 *
 * Square corners and no shadow, matching the artboard. The reference number is the screen's one
 * purple text: it is what an operator quotes on the phone.
 */
export function ProfileIdentityCard({
  referenceNumber,
  customerName,
  badges,
}: {
  referenceNumber: string;
  customerName: string;
  badges: ReactNode;
}): React.JSX.Element {
  return (
    <div
      style={{
        background: '#fff',
        border: `1px solid ${INK.BORDER}`,
        margin: '16px 20px 14px',
        padding: '14px 18px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 16,
        flexWrap: 'wrap',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 18, flexWrap: 'wrap' }}>
        <span style={{ fontFamily: NUMERIC_FONT, fontVariantNumeric: 'tabular-nums', fontSize: 21, fontWeight: 600, color: PALETTE.PURPLE }}>
          {/* dir="ltr" for the same reason as `Num` — a reference number carries no strong
              directional character to anchor itself on. */}
          <bdi dir="ltr">{referenceNumber}</bdi>
        </span>
        <span style={{ fontSize: 18, fontWeight: 600, color: PALETTE.TEXT }}>{customerName}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>{badges}</div>
    </div>
  );
}

/**
 * One of the three numbered sections.
 *
 * `note` is the grey line on the opposite end of the heading — «من السجل المدني — غير قابلة
 * للتعديل» and its siblings. It states the section's PROVENANCE and its editability together,
 * which is the whole point of the three-section split (AD-021): an operator should be able to
 * tell at a glance which part of the screen they may touch.
 */
export function SectionCard({
  heading,
  note,
  children,
}: {
  heading: string;
  note: string;
  children: ReactNode;
}): React.JSX.Element {
  return (
    <div style={{ background: '#fff', border: `1px solid ${INK.BORDER}`, marginBottom: 14 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          padding: '11px 16px',
          borderBottom: `2px solid ${PALETTE.PURPLE}`,
        }}
      >
        <h2 style={{ fontSize: 15, fontWeight: 600, color: PALETTE.TEXT, margin: 0 }}>{heading}</h2>
        <span style={{ fontSize: 11, color: INK.MUTED }}>{note}</span>
      </div>
      {children}
    </div>
  );
}

/** One of section 3's six sub-headings — «الحساب والفرع», «عنوان السكن» and the rest. */
export function SubSection({ heading, children }: { heading: string; children: ReactNode }): React.JSX.Element {
  return (
    <div>
      <div
        style={{
          padding: '9px 16px',
          background: INK.TINT,
          borderBottom: `1px solid ${INK.ROW_RULE}`,
          fontSize: 13,
          fontWeight: 600,
          color: PALETTE.TEXT,
        }}
      >
        <h3 style={{ margin: 0, fontSize: 'inherit', fontWeight: 'inherit' }}>{heading}</h3>
      </div>
      {children}
    </div>
  );
}

/**
 * The artboard's two-up field grid.
 *
 * A CSS grid rather than two hand-split columns, so a section with an odd number of rows does not
 * need the caller to decide where the break falls. `minmax(0, 1fr)` stops a long value widening
 * its column past its share.
 *
 * **EXACTLY TWO TRACKS, and that is load-bearing rather than a styling preference.** Callers
 * order their rows COLUMN-MAJOR to reproduce the artboard's columns -- section 1 emits
 * `5, 8, 6, 9, 7, 21` so that 5/6/7 land in one column and 8/9/21 in the other. That only works
 * at two tracks. A first cut used `repeat(auto-fit, minmax(min(100%, 380px), 1fr))`, which makes
 * `floor(width / 380)` tracks: three from about 1182px and FOUR at 1920px, scrambling the
 * interleaved order into a sequence no one chose. jsdom computes no layout, so every test passed
 * against it -- found at review, not by the suite.
 *
 * The narrow case still collapses to one column, which is the only other arrangement that keeps
 * the pairs legible: the artboard is a fixed 1240px canvas and has no narrow case to draw.
 */
export function FieldGrid({ children }: { children: ReactNode }): React.JSX.Element {
  return (
    <div
      style={{
        display: 'grid',
        // `min()` gives one track under 760px and two above it, with no media query and no
        // measurement -- and never three.
        gridTemplateColumns: 'repeat(2, minmax(min(100%, 380px), 1fr))',
      }}
    >
      {children}
    </div>
  );
}

export const ROW_STYLE: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 12,
  padding: '9px 16px',
  borderBottom: `1px solid ${INK.ROW_RULE}`,
  minHeight: 40,
};

export const ROW_NUMBER_STYLE: React.CSSProperties = {
  fontFamily: NUMERIC_FONT,
  fontVariantNumeric: 'tabular-nums',
  color: INK.FIELD_NUMBER,
  fontSize: 11,
  width: 22,
  flexShrink: 0,
  paddingTop: 3,
};

export const ROW_LABEL_STYLE: React.CSSProperties = {
  width: 132,
  flexShrink: 0,
  color: INK.LABEL,
  fontSize: 13,
};

export const ROW_VALUE_STYLE: React.CSSProperties = {
  flexGrow: 1,
  minWidth: 0,
  fontSize: 14,
  color: PALETTE.TEXT,
};

/** The page ground and the Arabic face, wrapping the whole screen. */
export function ScreenShell({ children }: { children: ReactNode }): React.JSX.Element {
  return (
    <div style={{ background: PALETTE.GROUND, minHeight: '100vh', fontFamily: ARABIC_FONT, fontSize: 14, color: PALETTE.TEXT }}>
      {children}
    </div>
  );
}
