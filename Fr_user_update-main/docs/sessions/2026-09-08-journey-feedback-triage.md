# 2026-09-08 — Journey-feedback triage: seven PO comments, the brand palette, and the S9 split

Assessment session. **No code was written, no plan-file rows were added, nothing was committed.**
The output is a set of decisions and a sprint split, plus one hard-rule flag on untracked files
now sitting in the working tree (§10).

Input: seven comments from the product owner and a colleague, both of whom walked the journey on
real devices. Two of the seven turned out not to describe the problem they appeared to describe;
one was already satisfied in code; one arrived mid-session with a design handoff attached that
changed its scope.

**Outcomes, stated up front:**

| # | PO comment | Disposition |
|---|---|---|
| 1 | Brand banner on every screen, circular logo | **Accepted, scope grew.** No shared screen shell exists; needs one. Asset already exists in the bank's other APK. → S9-03 |
| 2 | Account number: 1–11 digits, digits only | **Already satisfied except a cap.** Digits-only and no minimum are in place; backend deliberately validates no format. Only the 11-digit ceiling is new. → S9-01 |
| 3 | «التالي» hidden behind the Android nav bar | **Confirmed defect, systemic cause.** 14 of 21 screens have no `SafeArea`. → S9-01 |
| 4 | Auto-advance after OTP acceptance | **Accepted with a narrowed trigger.** The screen is multi-channel; the literal reading would skip channels. → S9-04 |
| 5 | Text colours from the brand palette | **Unblocked mid-session.** Palette PDF supplied; conflicts with the design handoff. Resolved — see §5. → S9-02 |
| 6 | «الرقم الوطني» → «البطاقة القومية» | **Accepted, narrowed to 3 of 6 sites.** The phrase names two different things. → S9-01 |
| 7 | Splash too fast to be seen | **Accepted as originally scoped.** A splash redesign arrived but is not approved; parked. → S9-01 |

---

## 1. Method

Every claim in the seven comments was checked against source before being planned. That is why
two of them changed shape. The brand assets were read from local disk only — the reference APK
was opened as a zip, no web fetch, no vendor documentation used as evidence.

The APK login credentials the PO offered were **not needed**. Only the brand assets were wanted,
and those extract from the archive without authenticating. Recorded because it saves a future
session the same request.

---

## 2. The two comments that were not what they looked like

### Item 2 — account number

Already correct in three of four respects. `account_entry_screen.dart` runs
`ArabicDigitInputFormatter` followed by `FilteringTextInputFormatter.digitsOnly`, so non-ASCII
digits are folded and non-digits rejected at the boundary. There is no minimum beyond non-empty,
so a single digit already submits. And the backend does not validate format at all:
`AccountCheckController.clean()` caps at `MAX_FIELD_LENGTH = 64` as an abuse bound, with its own
comment recording that the product owner previously **declined to assume an account-number
format** — `CheckAccount` decides validity, exactly as this comment asks.

So the only change is an 11-digit ceiling on the mobile field. Decided: a hard input cap, so the
twelfth keystroke is ignored rather than raising an error state that would need designing.

### Item 4 — OTP auto-advance

The verification screen is **not a single-OTP screen**. Per the project's three-independent-codes
rule it renders one row per pending channel from `pendingVerificationChannels()`, each with its
own field and its own «تحقق» button. «التالي» unlocks as soon as *any phone* channel verifies,
and if others remain unverified it opens a confirmation dialog that is a written journey rule
(customer.md Stage 2).

Taken literally, "advance when an OTP is accepted" would fire on the first accepted code, deny
the customer any chance to verify WhatsApp or email, and silently bypass that dialog. Raised with
the PO rather than implemented; see §7.

Worth separating from a decision it resembles: the code itself is deliberately **not**
auto-submitted at the sixth digit (a PO-approved guard, so a mid-paste transient cannot spend a
wrong-code attempt). That guard is untouched — this item is about what happens *after*
acceptance, not about triggering the attempt.

---

## 3. Item 3 — the defect, and why it is one fix and not fourteen

The colleague's screenshot is the contact-channels screen with «التالي» partly behind the
system navigation bar. The cause is systemic, not local: the footer pattern
`Column [ Expanded(scroll), Padding(16, FilledButton) ]` is repeated across the app with **no
`SafeArea`** — only 7 of 21 `Scaffold`s have one, and every affected screen is in the other 14.
With `targetSdk = 36` the app draws edge-to-edge, so 16 dp is all that separates the button from
the nav bar.

The app has no shared screen shell at all: 21 separate `Scaffold`s, 17 hand-written `AppBar`s,
and 4 screens with no app bar. This is also why item 1 is larger than it reads — the banner and
the footer inset are the same migration, which is the argument for a `JourneyScaffold` owning
both rather than two passes over the same 21 files.

---

## 4. Brand assets

Supplied at session start: the circular logo (`new-1.png`, `Last SFB.png`), banner screenshots,
the defect screenshot, and the bank's other Android app.

Supplied mid-session, after the initial assessment: `Color Paltted.pdf` and
`Mobile app banner redesign.zip`.

Two findings that remove work:

- The bank's other app is also Flutter, and ships `assets/sfb/logo-white-text.png` — **already the
  circular pearl logo plus the white Arabic/Latin wordmark on transparent**, which is precisely
  the lockup item 1 describes. Nothing needs drawing.
- The handoff zip supplies the same lockup as separate trimmed layers plus a written token
  system.

One finding that adds work: the handoff's own README states the wordmarks are raster extractions
from JPEG screenshots. Confirmed — `sfb-wordmark.png` is 429 × 150. Adequate for a 36 pt header
at 3×; it will soften anywhere larger. **Vector originals should be requested from the bank's
brand team now**, because that request has lead time.

### The handoff is broader than a banner, and is not approved

It contains an approved-marked splash redesign ("Splash C — horizon": a curved navy dune, a
camel-gait trek animation, ~2.45 s timeline), a header spec, and a token system that contradicts
this app's design system on three structural points — Amiri + Barlow in place of the bundled IBM
Plex Sans Arabic, radius 0 in place of 12–16 dp, and glow-and-ring depth in place of
depth-from-tone.

The PO confirmed the handoff is **still a proposal, not bank-approved**. Decisions in §7.

Recorded because it will come up again if Splash C is revived: this app's flat-fill surface
language was chosen deliberately, because the API-28 pilot handset runs the legacy renderer and
pays shader compilation. Splash C specifies a gradient sky, a radial glow and four infinite
animations. That is a measurable risk on the reference device and must be measured there before
it is committed to, not argued about.

---

## 5. The colour conflict, and how it was settled

Four navies were in circulation, all defensible, all visibly different:

| Colour | Source | Standing |
|---|---|---|
| `#040078` | `Color Paltted.pdf`, stated as "TV & Monitor Color RGB 4/0/120" | the bank's written brand spec |
| `#0b1c47` | the handoff's `navy` token, 21 uses across both artboards | sampled from screenshots — I measured the same value independently from `SFB-Screenshot-5.jpg` and `Banner1.jpg` |
| `#1F1D5C` | the circular logo's own ring | the artwork |
| `#000066` | the wordmark PNG in the bank's other app | that app's shipped asset |

Against which the app currently ships `#105097`, measured at S8-05 from
`docs/brand/sfb-logo-master-2048.jpg` — a different logo master, and confirmed this session to
genuinely be that colour. It is the outlier: **8.03:1 on white, against 16.45:1 for `#040078`.**

**PO decision: `#040078`.** Reasoning recorded with it — it is the only *documented* authority;
every other candidate is reverse-engineered from artwork. The accepted risk is that the app will
not exactly match the bank's own other app on screen, since that app ships `#0b1c47`.

The measurement that made this safe to accept, and the reason it is worth keeping:

```
#0b1c47 (handoff)   OKLCH  L=0.243  C=0.084  H=264.6
#040078 (brand)     OKLCH  L=0.261  C=0.178  H=265.4
```

**Same hue, effectively the same lightness. The entire difference is chroma — slightly more than
double.** So the handoff's compositions will read correctly with the official navy substituted;
this is a saturation change, not a different colour. That was not obvious from the hex values and
is the fact that de-risked the whole decision.

---

## 6. Derived accent tones

The handoff's supporting tones were tuned against `#0b1c47` and cannot be carried over unchanged.
Re-derived in OKLCH by transferring each tone's lightness delta, chroma ratio and hue offset onto
`#040078`:

| Token | Handoff | Derived | Contrast on white |
|---|---|---|---|
| `navy` | `#0b1c47` | **`#040078`** | 16.45:1 |
| `navy-deep` (gradient stop) | `#132a5e` | **`#010E99`** | 13.78:1 (was 13.81) |
| `steel` (accent) | `#5980a6` | `#1F85D9` *(rejected)* | 3.88:1 |
| `steel` (accent) | `#5980a6` | **`#677BA8`** *(recommended)* | 4.22:1 |

**The steel is a judgement, not arithmetic, and that is the point worth recording.** The
proportional transfer gives `#1F85D9` — a bright azure. It is the correct answer to "apply the
same relationship" and the wrong answer to the design intent, which is a *muted* accent for
contours, glows and rings. Keeping the original chroma and adopting only the brand hue gives
`#677BA8`: still muted, still the same role. Doubling the ground's saturation does not imply
doubling the accent's.

Constraint attached to it either way: **`steel` stays decorative.** At 3.9:1 on navy it is below
the body-text threshold. It is safe for contours and rings and would fail if anyone set a label
in it.

The rest of the text hierarchy is deliberately **not** fixed here. The theme's existing rule is
that every value is a role off a seeded `ColorScheme`, never a hand-picked hex; re-seeding from
`#040078` produces the neutral body and secondary tones automatically. Hand-picking them now
would be inventing values the theme is built to derive.

---

## 7. Decisions taken this session

| # | Decision | Made by | Reasoning |
|---|---|---|---|
| D1 | Brand navy is `#040078` | PO | Only documented authority; others reverse-engineered. §5 |
| D2 | Adopt the handoff's colour and banner only — keep IBM Plex Sans Arabic, rounded corners, depth-from-tone | PO | Smallest safe change; no regression risk across 21 existing screens. A full re-skin would mean a new font pipeline and re-checking every mixed Arabic/Latin line |
| D3 | Splash C is a proposal; not built | PO | Not bank-approved. Item 7 reverts to its original scope |
| D4 | Item 6 renames the 3 *document-type* sites only | PO, on recommendation | «الرقم الوطني» names both a card and a number; renaming the number labels would make them say "card" where they mean "number" |
| D5 | Item 4 advances only when **every** selected channel is verified | PO | The alternative skips channels and bypasses a written journey rule |
| D6 | `steel` = `#677BA8`, decorative only | recommendation, open | §6 |
| D7 | 11-digit account cap is a hard input cap | recommendation, open | No error state to design |

D3 reverses nothing that was built. **D1 does supersede a recorded decision** — D2.1 of
`2026-09-07-research-ui-ux-design-plan.md` pins `#105097` as "the single brand blue", named in
three places (Flutter theme, `res/values/colors.xml`, adaptive-icon background). S9-02 must move
all three together or they drift, which is the failure the original D2.1 comment exists to
prevent.

### Item 6 — the exact split

Renaming, all naming the *card*: `stage7_screen.dart:186` (document-type chooser),
`stage8_screen.dart:133` («بطاقة الرقم الوطني» in scan copy), and
`android/app/src/main/res/values-ar/strings.xml:85` (`uq_sdn_id_description`). Three widget tests
assert on that text and move with it.

Unchanged, all naming the *number*: `stage9_screen.dart:383` (extracted-value field label),
`backoffice/src/profiles/ProfileDetailPage.tsx:478` (`identityNumber`), and
`stage9_screen.dart:500` («الرقم الوطني غير صحيح» — reports that the extracted number is wrong).

### Item 4 — four consequences of D5, to be built into the spec

1. Because it fires only when nothing is unverified, `_onNext`'s confirmation dialog is
   unreachable by this path. The journey rule is not bypassed; it is inapplicable. **This is why
   D5 is the safe reading.**
2. Auto-advance must call `_onNext()`, never navigate directly — `_onNext` runs the reference-list
   preparation, which can fail and needs its retry. On failure the customer stays put with
   `_catalogError` shown, so **«التالي» must remain visible and enabled throughout.** Auto-advance
   is an addition, never a replacement.
3. A locked channel is never verified, so a customer who locks WhatsApp never triggers it.
   «التالي» stays their route out, dialog and all — unchanged.
4. The success beat is a timer; it must cancel on unmount, on abandon, and on a manual tap, or it
   double-navigates.

**Open sub-decision:** on a resume where every channel is already verified, should the screen
auto-advance on load? Recommendation: **no** — advancing with no interaction is disorienting and
the customer never sees why they moved. Auto-advance should only ever follow a verification the
customer just performed.

---

## 8. The S9 split

Four sessions. Token work is deliberately ahead of the banner — building the banner first means
building it twice.

| ID | Scope | Blocked by |
|---|---|---|
| S9-01 | Items 3, 2, 6, 7 — nav-bar overlap across all screens, 11-digit cap, document-type rename, splash minimum hold | nothing |
| S9-02 | Item 5 — `#040078` through the Flutter theme, `colors.xml` and the adaptive-icon background; text hierarchy re-seeded; contrast re-verified | nothing (D1 settled) |
| S9-03 | Item 1 — `JourneyScaffold` + banner, on the new tokens, with our abandon action in place of the handoff's sign-out button | S9-02 |
| S9-04 | Item 4 — auto-advance per D5 | the §7 sub-decision |

S9-01 carries the live defect and no design dependency, so it goes first regardless of the rest.

Item 7 folded back into S9-01 once D3 parked Splash C: with the existing splash retained, the fix
is again the small one — gate navigation on the existing 1800 ms animation completing, rather
than firing the instant the launch check resolves. That reverses D7.3 ("no minimum display time")
and must be recorded as such in S9-01, not slipped in. It invents no new number: the 1800 ms
choreography already exists and is simply never seen on a fast connection.

Two mismatches to carry into S9-03: the handoff's header has a **sign-out** button, and this app
has no session to sign out of — it has «التراجع عن الجلسة». Its scroll-collapse behaviour and
sample dashboard belong to the bank's other app, not to a 12-stage form journey.

---

## 9. Open items

- §7 sub-decision on resume-time auto-advance (blocks S9-04 only).
- D6 and D7 are recommendations, not yet confirmed.
- Vector originals of the wordmark and circular logo, from the bank's brand team.
- Whether the circular logo also replaces the octagon on the splash and the Android launcher icon.
  Raised, not answered. Currently the launcher ships the octagon.
- The colleague's device model and Android version, so S9-01's fix is verified against the device
  that reported it.

---

## 10. How design work is done from here — and how the PO reviews it

Decided this session, after the PO asked whether to manage design in the Claude Design desktop
app. **This section is binding on every later design session.**

**Decision: design canvases are authored in the Claude Code session that holds the repo**, and
published as an artifact the PO opens in a browser. The Claude Design desktop app is reserved for
two cases — handing a canvas to the bank's brand team, and PO iteration with no session running.

### Reasoning, evidenced by this session's own input

The one design handoff produced *without* repo context — `Mobile app banner redesign.zip` — over-
reached on five points, every one of them a briefing gap rather than a design failure:

| What it specified | What it did not know |
|---|---|
| A sign-out button in the header | This app has no session to sign out of — it has «التراجع عن الجلسة» |
| A sample dashboard with balance and quick actions | That is the bank's *other* app; this is a 12-stage form journey |
| Amiri + Barlow | IBM Plex Sans Arabic was chosen for its **matched Latin** — Arabic labels sit beside Latin digits and `SFB-` reference numbers on nearly every screen |
| Radius 0 everywhere | The app's fields, cards and tiles are 12–16 dp by decision |
| Gradient sky, radial glow, four infinite animations | Flat fills were chosen because the API-28 pilot handset runs the legacy renderer and pays shader compilation |

The constraints live in code comments and session reports, not in the visual layer. The party
holding the repo is therefore the party that must brief the design — which is the whole argument
for authoring the canvas here.

### The review loop — the PO does not build an APK to see a design

1. Session publishes the canvas as an artifact; the PO gets a URL and opens it in a browser.
2. Where the canvas editor is enabled for the account: click-to-select on any element, a
   properties panel, inline text editing, undo/redo. Save publishes a new version.
3. A save republishes the artifact; a session watching it is notified, re-reads the canvas, and
   implements **from what the PO actually changed** — not from a screenshot or a second verbal
   description. That is the loop the zip handoff could not close, and why every conflict in the
   table above had to be caught by hand after the fact.

**Unresolved at the time of writing:** the canvas editor is gated per account and could not be
verified from inside the session. The first board, published this session, is the test. If the
editor is **not** enabled, the PO gets view plus PNG/PDF export only, design moves to the desktop
app, and this session's obligation becomes writing the constraints brief — screens, journey
stages, Arabic copy, error states, and the five decisions in the table above — for the PO to
paste in. Either branch leaves the next session better briefed; **record which one applies.**

### Scope discipline

This is **not** a redesign of the built app. A full pass over the 21 screens was considered and
rejected: it would silently overwrite the five load-bearing decisions above, and it is a sprint of
Flutter re-implementation ahead of a pilot. Design work is scoped to:

- the banner (S9-03),
- colour application (S9-02),
- **the states never art-directed** — the terminal screen, the offline banner, session-pending,
  session-complete, the confirmation screen, the scan-blocked view, and the final-stages gate.
  The handoff's own README independently flagged this gap ("States not yet designed: loading
  skeletons, error/offline banner…"). Roughly eight artboards, not twenty-one screens.

### `/design-sync` — assessed and declined for now

Checked this session at the PO's request. It publishes a local component library **up** to Claude
Design so generated designs match real components; it is not a route from Claude Design into this
repo, which is the direction the PO wanted. It also has nothing here to read: it consumes tokens
and React components, the mobile design system is Dart, and `backoffice/` is an antd consumer app
— `main.tsx` sets `direction="rtl"` and a locale with **no theme tokens at all**, `index.css` is a
21-line reset, and `src/` holds pages and modals rather than reusable components.

If an SFB design system is wanted in Claude Design later, it must be created rather than synced,
and **not before S9-02** — syncing today would register `#105097`, the navy D1 replaces.

---

## 11. Not done — and a hard-rule flag

No code changed. No gate was run, because nothing was built; there is no gate output to paste and
none is claimed. No rows were added to BACKLOG.md or EXECUTION_PLAN.md, and nothing was committed
— the PO redirected to writing this report first.

**`resources_for_design/` is untracked in the working tree and must not be committed as-is.**
Two problems, either one sufficient:

1. **Real customer data.** `Banner1.jpg` is a screenshot of the bank's other app showing a live
   account number, balance and account-holder name; `action_button_low.jpeg` carries a phone
   number. CLAUDE.md forbids real customer data and live account numbers in the repo, in
   fixtures, in prompts and in session reports. None of those values are reproduced in this
   report, deliberately.
2. **A 76 MB third-party APK**, which does not belong in this repository's history under any
   reading.

Recommended before any commit touching that path: add `resources_for_design/` to `.gitignore`, or
move the folder outside the repository entirely and keep only the derived, PII-free assets
(the circular logo and the white wordmark) under `mobile/assets/brand/` when S9-03 needs them.
The extracted working copies used this session live in the scratchpad, not in the repo.
