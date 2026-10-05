# Handoff: Sudanese French Bank — "بياناتي" mobile app (splash + app header)

## Overview
Two design artifacts for the SFB mobile banking app **بياناتي** ("Bayanati"), tagline **لؤلؤة المصارف**:

1. **Splash screen** — three explored versions; **Splash C ("horizon") is the approved one**.
2. **App header banner** — the existing web banner reworked for mobile, with the octagonal mark replaced by the circular pearl logo, and restyled to match Splash C.

## About the design files
The files in `designs/` are **design references authored as HTML** — prototypes showing intended look and motion, not production code to copy. Recreate them in the target codebase's own environment (React Native, Flutter, SwiftUI, Kotlin/Compose, React web…) using its established components and patterns. If no app environment exists yet, pick the framework that fits the project and implement there.

They are single-file HTML documents that open directly in a browser; the visual layer is plain inline CSS, so every value can be read off the markup.

## Fidelity
**High fidelity.** Colors, type sizes, spacing, and animation timings are final and should be matched. The one exception: the Arabic wordmark and the pearl logo are raster images extracted from supplied screenshots (see **Assets**) — request the vector originals from the bank before shipping.

---

## Screens / Views

### 1. Splash C — "horizon" (APPROVED)
File: `designs/SFB Splash Screen.dc.html`, third phone frame (label "Splash C — horizon").
Canvas: 390 × 844 pt (iPhone logical size). Everything below is in pt at that size.

Purpose: cold-start brand moment, ~2.5 s before the login screen.

Layout, top to bottom, all absolutely positioned inside the 390×844 frame:

| # | Element | Geometry |
|---|---|---|
| 1 | Navy "sky" with a curved dune bottom edge | SVG, 390 × 500, anchored top-left, `preserveAspectRatio="none"` |
| 2 | Pearl logo, centered horizontally | Center at y = 404; 112 pt circle, inside a 156 pt stage |
| 3 | App name `بيــانــاتــي` | Block starts y = 520, centered |
| 4 | Hairline rule | 46 × 1, `rgba(11,28,71,.35)` |
| 5 | Tagline `لؤلؤة المصارف` | 23 pt |
| 6 | Bank wordmark (navy) | 210 pt wide, bottom: 74 |
| 7 | Progress hairline | 120 × 1.5, bottom: 44 |

**The dune.** One SVG (`viewBox 0 0 390 500`) holding four paths:
- Sky fill: `M0,0 H390 V372 C312,432 250,392 195,404 C132,418 84,462 0,420 Z`, filled with a vertical gradient `#0b1c47 → #0b1c47 (62%) → #132a5e`.
- Same path again filled with a radial glow, center 50% / 92%, r 62%, `#5980a6` at 50% → 0%.
- Accent contour 1: `M0,436 C88,478 134,434 197,420 C252,408 314,448 390,388`, stroke `#5980a6` @ 45%, 1.2 wide.
- Accent contour 2: `M0,468 C96,504 140,462 203,450 C258,440 318,476 390,420`, stroke `#5980a6` @ 22%, 1 wide.

The sky is *not* a straight split — the curved edge is the signature of the design; a flat divider was explicitly rejected.

**Pearl logo stage** (156 × 156, centered on the dune crest):
- Halo: 156 circle, `radial-gradient(circle, rgba(255,255,255,.30) 0%, transparent 68%)`, pulsing.
- Ring: 140 circle, 1 pt border `rgba(89,128,166,.55)`.
- Logo: 112 circle, white fill, `object-fit: cover`, `border-radius: 50%`.
- Two dust dashes at the lower-left of the stage: 16 × 2 and 11 × 2, radius 2, `rgba(89,128,166,.7)` / `.55`.

**Typography**
- App name: Amiri 400, 62 pt, line-height 1, `#0b1c47`, `dir="rtl"`. The tatweel elongation is part of the string: `بيــانــاتــي` (U+0640 ×2 after ي, ا, and ت).
- Tagline: Amiri 400, 23 pt, line-height 1.5, `#0b1c47`.
- Amiri from Google Fonts (weights 400, 700). Bundle it locally in production.

**Motion** (all `both` fill mode, run once unless noted):

| Animation | Target | Keyframes | Duration / delay / easing |
|---|---|---|---|
| `sfb-trek` | logo stage | `translateX 190 → 0`, opacity 0 → 1 by 18% | 1.5 s, 0 s, `cubic-bezier(.25,.8,.3,1)` |
| `sfb-bob` | logo circle | `translateY 0 → -5 → 0` with `rotate -0.6° → 0.6° → -0.6°` | 2.1 s, delay 1.5 s, ease-in-out, **infinite** |
| `sfb-halo` | halo | opacity .42 → .72 → .42, scale 1 → 1.06 → 1 | 4.2 s, ease-in-out, **infinite** |
| `sfb-dust` | two dashes | opacity 0 → .5 → 0, `translateX 10 → 46`, scale .7 → 1.25 | 2.6 s, delays 1.4 s / 2.0 s, ease-out, **infinite** |
| `sfb-rise` | name block, wordmark | opacity 0 → 1, `translateY 14 → 0` | 0.9 s @ 1.15 s; 1.0 s @ 1.45 s; `cubic-bezier(.2,.7,.2,1)` |
| `sfb-load` | progress fill | `translateX -100% → 0` | 2.4 s, `cubic-bezier(.4,0,.2,1)`, **infinite** |

The rider travels in from the right and settles into a camel-gait bob — the logo is a rider on a camel, so the bob and the dust dashes read as motion across the dune. Respect `prefers-reduced-motion`: render the settled end state, keep only the halo, or drop motion entirely.

### 2. Splash A (navy) and Splash B (paper) — not approved
Same file, first two frames. Kept for reference only; do not implement unless asked.

### 3. App header banner
File: `designs/SFB Mobile Banner.dc.html`. Four variants of the same header:

- **Full width** — desktop/tablet width, 54 pt mark, 44 pt wordmark height, 44 pt sign-out button.
- **In place, 390 pt** — mobile header inside a sample dashboard screen (status bar, greeting, balance card, three quick actions). 46 pt mark, 36 pt wordmark, 38 pt button.
- **Compact / scrolled** — 34 pt mark, 26 pt wordmark, 34 pt button.
- **Mark only** — centered 44 pt mark, sign-out pinned right.

Common anatomy, left to right: pearl mark → Arabic + Latin wordmark (white) → flexible spacer → outlined sign-out button. Bar background `#0b1c47`.

Shared treatments carried over from Splash C:
- **Glow**: overlay `radial-gradient(65% 190% at 14% 65%, rgba(89,128,166,.34) 0%, transparent 68%)` behind the mark.
- **Dune edge**: SVG at the bar's bottom (`viewBox 0 0 390 28`, `preserveAspectRatio="none"`, `bottom:-1px`), fill = the color *below* the bar (`#ffffff` inside the phone screen, `#f2f2f3` on the page ground), path `M390,2 C312,15.3 250,6.4 195,9.1 C132,12.2 84,22 0,12.7 L0,28 L390,28 Z`, plus the same open path stroked `#5980a6` @ 55%, 1 pt non-scaling.
- **Pearl ring**: `box-shadow: 0 0 0 1.5px rgba(89,128,166,.8)` on the mark (1.2 px on the small variants).
- The bar's bottom padding is enlarged (26–34) so content clears the curve; content sits above the overlays (`position: relative`).

**Sign-out button**: square, transparent, 1.2–1.4 px border `rgba(255,255,255,.85)`, white Lucide `log-out` glyph at stroke-width 1.5. Hover `rgba(255,255,255,.12)`, pressed `rgba(255,255,255,.2)`. Never below 44 pt of touch target on mobile — pad the small variants' hit area even though the visual box is 34–38.

**Sample dashboard content** (in-place variant only, placeholder to show the header in context): greeting "Good morning, Amir" (Barlow Condensed 22), account card — kicker "CURRENT ACCOUNT" (11, letterspacing .16em, `#5980a6`), balance "SDG 412,900.00" (Barlow Condensed 30, tabular numerals), masked number "•••• 4417"; three equal quick-action cells: Transfer / Pay bill / Cards.

---

## Interactions & behavior
- **Splash**: no interaction. Runs its timeline, then routes to login/auth. Total scripted timeline ≈ 2.45 s (last entrance ends at 1.45 s + 1.0 s); the progress hairline loops until routing completes. Hold the splash until app bootstrap resolves, minimum ~1.6 s so the trek animation isn't cut.
- **Header sign-out**: confirm dialog, then clear session and return to login.
- **Scroll**: the full header collapses to the compact variant as the screen scrolls (target ~120 ms cross-fade / height transition).
- **RTL**: the app is Arabic-first. In RTL the header mirrors — mark and wordmark to the right, sign-out to the left. The dune curve should mirror too (`scaleX(-1)`).
- **States not yet designed**: loading skeletons, error/offline banner, notification badge on the header. Ask the designer before inventing them.

## State management
Minimal for these two views:
- `splashDone: boolean` — set when the animation timeline and app bootstrap both resolve.
- `bootstrapStatus: 'loading' | 'ready' | 'error'` — drives whether the splash routes on or shows an error path (not yet designed).
- `session` / `user.displayName` — feeds the header greeting.
- `headerCollapsed: boolean` — derived from scroll offset.

## Design tokens

Colors
| Token | Value | Use |
|---|---|---|
| `navy` | `#0b1c47` | brand ground, header bar, primary text on paper |
| `navy-deep` | `#132a5e` | lower stop of the sky gradient |
| `steel` | `#5980a6` | accent: contours, glow, rings, kickers |
| `paper` | `#ffffff` | screen ground |
| `ground` | `#f2f2f3` | page/canvas ground behind the artboards |
| ink on navy | `#ffffff`, plus `.86 / .55 / .22 / .15` alphas | reversed type and hairlines |

Type
- Arabic display: **Amiri** 400 — 62 (app name), 23 (tagline).
- Latin headings: **Barlow Condensed** — 30 (balance), 22 (greeting), 13 (labels, letterspacing .18em, uppercase).
- Latin body: **Barlow** — 12–16.

Spacing: 4-pt base. Used steps 6, 10, 12, 14, 16, 18, 26, 34, 36, 44, 56, 74.
Radius: **0** everywhere except the circular logo (`50%`) and the 2-pt dust dashes. Square corners are deliberate.
Shadows: none. Depth comes from the accent glow and the pearl ring only.
Motion easings: `cubic-bezier(.2,.7,.2,1)` (entrances), `cubic-bezier(.25,.8,.3,1)` (trek), `cubic-bezier(.4,0,.2,1)` (progress), `ease-in-out` (loops).

## Assets
In `assets/`:
- `sfb-logo-circle.png` — 1318 × 1318, circular pearl logo with the camel rider, alpha-clipped to the circle. Derived from the supplied `Last SFB.png`.
- `sfb-wordmark.png` — 429 × 150, Arabic + Latin wordmark recolored **white** on transparent, extracted from the supplied navy banner screenshot. Used on navy grounds.
- `sfb-wordmark-navy.png` — 631 × 207, same wordmark in `#0b1c47` on transparent, extracted from the supplied white splash screenshot. Used on paper grounds.
- `source-logo-original.png` — the original circular logo as supplied, untrimmed.

⚠ The two wordmarks are raster extractions from JPEG screenshots and carry mild edge artifacts at large sizes. Get the vector (SVG/AI) originals from the bank's brand team before release, and export the logo as SVG or a 3× PNG set.

Icons: **Lucide**, stroke-width 1.5 — `log-out` in the header, signal/battery glyphs in the mock status bar (drop those; use the platform status bar).

## Files
- `designs/SFB Splash Screen.dc.html` — three splash versions side by side; **Splash C is the approved one**.
- `designs/SFB Mobile Banner.dc.html` — four header variants plus a sample dashboard screen.

Both files reference a shared stylesheet at `_ds/industry-…/styles.css` for a handful of design-system tokens and the hairline "blueprint" frames drawn around each artboard. That stylesheet is **not** part of the design — the frames and corner ticks are presentation chrome for reviewing the artboards, not UI. Ignore any missing-stylesheet fallback; every value that matters is inline in the markup.
