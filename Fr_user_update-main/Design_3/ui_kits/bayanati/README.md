# Bayanati — UI kit

Click-through recreation of the one-time account-data-update journey for the Sudanese French Bank app
(**بياناتي**, tagline **لؤلؤة المصارف**).

Open `index.html`. The left frame is a 390×844 device canvas; the right rail jumps between screens.

## What this covers

| Screen | Route in the Flutter app |
| --- | --- |
| `LaunchScreen` | `/` |
| `AccountEntryScreen` | `/account-entry` (step 1a) — also renders the not-found + offline state |
| `ContactChannelsScreen` | `/contact-channels` (step 1b) |
| `IdentityTypeScreen` | `/stage-7` |
| `RegistryReviewScreen` | `/stage-9` |
| `SubmitScreen` | `/stage-12` |
| `ConfirmationScreen` | `/confirmation` |

The remaining routes — stages 3–6 data entry, the stage-8 scan brief and its failure/blocked states,
stage-10 liveness, stage-11 signature, session complete, terminal, and the `/final-stages` resume gate —
are drawn at full fidelity in `Bayanati Screens.dc.html` at the project root, in three directions
(Navy, Paper, Horizon). Horizon is the direction this design system encodes.

## Rules this kit demonstrates

- **Navy is spent exactly twice** — the launch splash and the confirmation. Every other end state is white,
  so "you're done" stays visually unique in a journey a customer sees once.
- **One primary action per screen**, pinned to the bottom. Abandoning lives in the header.
- **The brand bar rides every screen except launch**, where the pearl is already the hero.
- **Numerals**: Arabic-Indic digits may be typed and are transliterated to ASCII before storage; everything
  displayed is Latin, tabular and direction-isolated. Dates display DD/MM/YYYY.
