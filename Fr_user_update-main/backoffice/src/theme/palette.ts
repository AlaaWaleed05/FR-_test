/**
 * The approved back-office palette — `docs/backoffice-redesign.md` §1, "The palette, sampled and
 * approved". Sampled from the AZ brand assets and signed off 2026-09-16 with the artboards at
 * `Design_3/backoffice/approved/`.
 *
 * Here so no screen re-types a hex. These are the AZ identity, and AZ branding is BACK-OFFICE
 * ONLY — the mobile app is the bank's and keeps the SFB identity, so nothing in `mobile/` may
 * import from this idea, let alone this file.
 *
 * Two notes the brief records and that are easy to lose:
 *
 * - **The grey `#ABADAC` is deliberately absent.** The two-colour rule under the header is red
 *   and purple, the purple taking the grey's former width. Reintroducing a third band is a
 *   design change, not a styling choice.
 * - **`RED` has no counterpart in the AZ login palette.** It comes from the letterhead and
 *   stands in until the product owner supplies a replacement. It is the reject action and the
 *   narrow band of the rule, and nothing else.
 *
 * `SUCCESS` is scoped harder still: the liveness chip, and only that. It is not a general
 * "good" colour, and using it for one would put the same green on things an operator must read
 * differently.
 */
export const PALETTE = {
  /** Primary action, section accents, the reference number. */
  PURPLE: '#4B237E',
  /** The sign-in panel's gradient, and hover against `PURPLE`. */
  PURPLE_DARK: '#351A63',
  /** The sign-in panel's gradient. */
  BLUE: '#234B8F',
  /** Body ink. Every rule, label and muted tone below is derived from it. */
  TEXT: '#20212A',
  /** Control borders on the sign-in. */
  BORDER: '#CFD1D8',
  /** The page behind the cards. */
  GROUND: '#EEF0F4',
  /** Reject, and the narrow band of the two-colour rule. */
  RED: '#E4312A',
  /** The liveness chip ONLY. */
  SUCCESS: '#2F7D5D',
} as const;

/**
 * The tones the artboards build out of `TEXT` rather than out of new hexes — card borders, row
 * rules, field labels, the field numbers down the side. Derived here once so a screen never
 * inlines `rgba(32,33,42,0.14)` and leaves the next reader to work out where it came from.
 *
 * The alpha values are read straight off `profile-screen.dc.html`; the names say what each one
 * is FOR, because the same alpha does two jobs in places and a name like `ALPHA_14` would not
 * survive a redesign.
 */
export const INK = {
  /** Card and section borders. */
  BORDER: 'rgba(32,33,42,0.14)',
  /** The rule between two field rows. */
  ROW_RULE: 'rgba(32,33,42,0.08)',
  /** Field labels, and the section's own "not editable" note. */
  LABEL: 'rgba(32,33,42,0.72)',
  /** Header meta, sub-captions, secondary lines. */
  MUTED: 'rgba(32,33,42,0.64)',
  /** The form-field numbers running down the side of each row. */
  FIELD_NUMBER: 'rgba(32,33,42,0.48)',
  /** The tint behind a tile, a badge or an editing input. */
  TINT: 'rgba(75,35,126,0.07)',
  /** The well an artifact tile sits in. */
  WELL: 'rgba(75,35,126,0.05)',
} as const;

/**
 * Latin digits, dates and identifiers, so columns of numbers line up and an account number does
 * not sprawl. Applied per-span rather than to a whole row, because the Arabic around it must
 * stay in the Arabic face.
 *
 * **The artboards name `IBM Plex Sans Condensed` here and this does not load it.** That face is
 * not vendored in this repo, and the only way to get it would be a runtime request to a font CDN
 * -- which `index.css` explains at length is the thing this tier must not depend on, because the
 * bank's default-deny egress blocks it silently. What the artboards actually NEED from it is
 * `font-variant-numeric: tabular-nums`, which is a CSS property rather than a property of the
 * face and works in any of the fallbacks below. What is lost is the condensed WIDTH of Latin
 * numerals, which is cosmetic. Recorded so the next reader does not "fix" this back to a CDN.
 */
export const NUMERIC_FONT = "'IBM Plex Sans Condensed', system-ui, 'Segoe UI', Arial, sans-serif";

/**
 * The screen's Arabic face, matching both artboards' `helmet` block -- served from
 * `public/fonts/` by `index.css`'s `@font-face` rules, never from a CDN.
 *
 * `login.dc.html` additionally names `Inter` for Latin and this deliberately does not load it:
 * Plex ships a Latin designed to match its Arabic, so one self-hosted family covers both scripts
 * where two would have meant a second network dependency for the two decorative English labels
 * on the sign-in.
 */
export const ARABIC_FONT = "'IBM Plex Sans Arabic', system-ui, 'Segoe UI', Arial, sans-serif";
