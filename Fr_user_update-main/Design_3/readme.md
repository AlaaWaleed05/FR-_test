# Bayanati Design System

The design system for **بياناتي** (*Bayanati*) — the Sudanese French Bank's one-time account-data-update
app. Tagline **لؤلؤة المصارف** ("the pearl of banks").

## What this product is, and is not

Bayanati is **not** a banking app. There are no balances, no transfers, no dashboard and no tab bar.
It is a single Arabic-first journey of thirteen steps that a customer completes **once per account**,
to refresh the details the bank holds on file. Every screen is a step; the only navigation is forward,
back, or abandon. Design accordingly — anything that implies "come back tomorrow and browse" is wrong.

The journey: account number + branch → choose contact channels → verify them by OTP (three independent
channels) → personal and social data → occupation and income → home address → work address → identity
document type → document scan (Uqudo SDK) → review what the civil registry returned → liveness →
signature → submit. Then a reference number, and it is over.

## Sources

- **Code**: `github.com/Osmantou/Fr_user_update`, branch `main`, Flutter app under `mobile/`.
  Screens under `mobile/lib/features/`, theme in `mobile/lib/core/theme/app_theme.dart`,
  routes in `mobile/lib/core/router/app_router.dart`, provenance rules in `docs/journeys/field-provenance.md`.
  All Arabic copy in this system is lifted verbatim from those files.
- **Brand master**: `docs/brand/sfb-logo-master-2048.jpg` in that repo — white wordmark and the
  **octagonal** mark on #105097.
- **Owner palette**: a one-page CMYK PDF supplied in chat; four deep navy bands (#111523, #0f162b, #0c193a, #0e2061).
- **Existing banner + circular logo**: screenshots supplied in chat.

### Two unresolved brand conflicts — read before you design

1. **Which blue.** `app_theme.dart` uses **#105097**, measured off the logo. The owner's palette PDF is
   **deep navy**. This system follows the owner's palette (`--navy-800: #0b1c47`). If the bank rules
   for #105097, change `tokens/colors.css` and everything follows.
2. **Which mark.** The brand master carries an **octagonal** mark; the app and every design here use the
   **circular pearl** the client supplied. Confirm which is current before release.

## Content fundamentals

- **Language**: Arabic, RTL, always. Latin appears only in labels (Barlow Condensed), numerals, and
  the English name field returned by the registry.
- **Voice**: plain, factual, second person, no exclamation, no emoji, ever. The app tells the customer
  what happened and what to do next, in one short sentence. "تعذر العثور على هذا الحساب. يرجى التحقق من الرقم."
- **Honesty about state** is the strongest rule in the copy. The app never says a request was approved —
  only that it was received for review: "تم إرسال طلبك إلى البنك للمراجعة والاعتماد" followed immediately
  by "لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة."
- **Errors say whether an attempt was counted.** Scan failures distinguish "تم احتساب محاولة" from
  "لم يتم احتساب أي محاولة" — the customer has a hard attempt budget and is told where they stand.
- **Reassurance where work could be lost**: "كل ما أدخلته وتم التحقق منه محفوظ، وستتابع من هذه الخطوة عند عودتك."
- **Going offline is not an error.** The offline banner is a plain statement in a warn tone, never red.
- **Numerals**: customers may type Arabic-Indic digits — `ArabicDigitInputFormatter` transliterates them
  to ASCII before anything is sent. Everything **displayed** is Latin, tabular, and direction-isolated
  (`LtrValue` standalone, FSI/PDI when interpolated). Dates display DD/MM/YYYY, never ISO.

## Visual foundations

- **Ground**: white screens (`--paper`) on a light canvas. Navy (`--navy-800`) is a *field*, spent
  exactly twice in the whole app — the launch splash and the confirmation — so arrival feels distinct.
- **Accent**: one steel (`--steel-500`). No second hue. Green and red appear only as semantic marks,
  never as fills larger than a chip or an icon frame.
- **Corners are square.** `--radius: 0`. The only circle in the system is the pearl mark; the only oval
  is the liveness camera target, which is an affordance, not a style.
- **No drop shadows anywhere.** Depth comes from three things: the steel radial glow behind the brand
  bar, the 1.5px steel ring around the pearl, and the dune curve.
- **The dune curve** is the signature. It is not a decorative wave — it is an **arc concentric with the
  pearl**: the horizon is a 70px-radius arc centred on the logo's centre, so the mark nests into it, and
  the two accent contours are wider arcs from the same centre, reading as ripples. A straight divider
  was explicitly rejected. See `guidelines/brand-dune.card.html`.
- **Boundaries are load-bearing.** Every field carries a 1px `--line-field` hairline at 3.4:1 on paper,
  clearing the 3:1 control-boundary rule — the app's own theme argues for this and it is kept.
- **Type — one matched superfamily, plus one display face.** Interface text is **IBM Plex Sans Arabic**,
  whose Arabic was designed as a companion to IBM Plex Sans Latin; Latin labels and step counters use
  **IBM Plex Sans Condensed** from that same superfamily, so the two scripts share proportions, weight and
  rhythm. **Amiri** (Naskh serif) is the **splash face only** — the launch screen, and only ever setting
  Arabic. The confirmation, which used to be Amiri, is now Plex.
  Tracking is **0** on Arabic (positive tracking breaks the joins) and .14em on Latin labels.
  Body never below 16, helper never below 14, no italic.
- **Backgrounds**: flat colour. No photography, no illustration, no gradient beyond the two brand glows.
- **Animation**: entrances rise 14px and fade over 0.9s on `--ease-entrance`; the launch mark treks in
  from the right then settles into a camel-gait bob with dust dashes (the mark is a rider on a camel);
  the halo pulses on a 4.2s loop; progress is an indeterminate hairline. Honour `prefers-reduced-motion`.
- **Hover / press**: hover is a steel tint at 12%; press darkens the navy to `--navy-700`. Focus is a
  1.5px steel boundary plus a 2px `--focus-ring` outline. Disabled drops to 45% opacity.
- **Tap targets** never below 44px, even where the visual box is smaller.

## Iconography

**Lucide, stroke-width 1.5**, drawn inline as SVG at 15–30px. No icon font, no PNG icons, no emoji, no
unicode glyphs as icons. The Flutter app uses Material's outlined set; Lucide is the closest match at
this weight and is what every component here draws — **flag this substitution** if the team wants the
Material set instead.

Two icons are deliberately hand-built rather than taken from a set, because no icon library has them:
the **document depictions** in `DocumentCard` (photo box, data lines, MRZ rows) and the **capture
targets**. These are schematic on purpose — never substitute artwork of a real Sudanese ID or passport.

The WhatsApp glyph used for the channel list is a generic outline mark, **not** Meta's brand asset;
licensing that mark is still an open decision on the app side.

## Index

| Path | What |
| --- | --- |
| `styles.css` | The one entry point. Imports everything below. |
| `tokens/` | `colors`, `typography`, `spacing`, `motion`, `elevation`, `fonts` |
| `components/brand/` | `BrandBar`, `Pearl`, `DuneEdge` |
| `components/forms/` | `Field`, `TextInput`, `PickerField`, `PhoneField`, `OtpInput`, `Checkbox`, `Radio`, `SegmentedControl` |
| `components/actions/` | `Button`, `IconButton`, `ActionBar` |
| `components/navigation/` | `StageHeader`, `ScreenTitle`, `ProgressBar`, `LoadingRule` |
| `components/feedback/` | `Banner`, `OfflineBanner`, `Tag`, `TerminalState` |
| `components/data/` | `ReviewList`, `ReviewRow`, `ChannelRow`, `IdentityPlate`, `ReferencePlate` |
| `components/capture/` | `CaptureFrame`, `DocumentCard`, `SignaturePad` |
| `ui_kits/bayanati/` | Click-through of the journey — open `index.html` |
| `guidelines/` | Foundation specimen cards |
| `assets/` | Pearl mark, wordmark in white and navy, brand master |

Full 22-screen sets in three directions live at the project root:
`Bayanati Screens.dc.html` (Horizon — the direction this system encodes),
`Bayanati Screens - Set A Navy.dc.html`, `Bayanati Screens - Set B Paper.dc.html`.

## Intentional additions

- **`ProgressBar` / the 13-step counter.** The Flutter app has no progress indicator today. A thirteen-stage
  journey with no sense of remaining effort is the largest UX gap in the product, so the system provides one.
  It is a proposal, not a recreation.
- **`DocumentCard`.** The app uses a plain radio list for identity-document type; the card depiction was
  added so customers recognise the document by sight rather than by reading.

## Assets that are not real yet

- The wordmark PNGs are **raster extractions from JPEG screenshots** and carry mild edge artifacts at large
  sizes. Get the vector originals from the bank's brand team before release.
- No font binaries were provided. All three families load from Google Fonts; the app already bundles
  IBM Plex Sans Arabic locally, so only IBM Plex Sans Condensed and Amiri need adding. Swap
  `tokens/fonts.css` for real `@font-face` rules when the files land.
