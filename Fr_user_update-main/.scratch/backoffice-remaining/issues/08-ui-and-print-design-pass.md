# What is wrong with the back office today, and what should the print look like?

> **SUPERSEDED IN PART BY AD-022 (2026-09-16), on one point only.** The form does not ship in TWO variants; there is one. Everything else in
> this ticket still stands and is still the reason the current design is what it is. This is a
> dated decision record, not a live spec — it is stamped rather than rewritten or deleted.

Type: prototype
Status: resolved
Blocked by: 04

## Question

Added by the product owner on 2026-09-13: improve the back-office UI, and design the print layout.

Two halves, one ticket because they share a visual language.

**The screen.** The back office is Ant Design, Arabic-first, RTL. It has a login, a password
change, an admin landing page, a profile list and a single profile view with reject and
manual-complete modals. Before redesigning anything, establish what is actually wrong — walk it
and write down the specific failures, rather than restyling on instinct. Known additions landing
on top of it: real images (tickets 01/02), a print action, an admin screen (07).

1. What does an operator's real working day look like — how many profiles, how long on each, what
   do they reach for first? The design follows that, not the other way round.
2. What is genuinely broken today versus merely plain?
3. Does the list need the export button that `BL-026` says is missing, and does that fold in here?
4. RTL specifics that go wrong in Ant Design: preview-arrow direction, number and date direction,
   table column order, icon mirroring.

**The print.** Once ticket 04 fixes the field set, lay it out: header, numbered footer, the title
استمارة تحديث البيانات, customer name and reference number prominent, the mobile submission date,
the printing operator and the print timestamp, and the body.

5. A4 or Letter? Sudan uses A4 — confirm.
6. What does the header carry — bank name, logo, branch?
7. How does a multi-page form stay readable: repeated header, "page N of M", continuation markers?
8. Prototype it as HTML first and react to it, before anyone writes PDF code against ticket 03's
   chosen library.

## Context

- `backoffice/src/` — the whole app is 40 files; read it before proposing changes
- `docs/brand/` · `Design_3/` — existing visual material
- `docs/journeys/operator.md` — what the operator is actually there to do
- `AD-006`-adjacent: mobile's design language, for consistency where it matters
- **No real customer data in any prototype or screenshot.**

## Carried in from ticket 03 (2026-09-13)

**The back office bundles no Arabic face at all.** `backoffice/src/index.css:19` sets
`font-family: system-ui, 'Segoe UI', Tahoma, Arial, sans-serif` — so every Arabic glyph in the
operator UI is whatever the operator's Windows install happens to have, while mobile ships two
licensed faces (`IBMPlexSansArabic-Regular.ttf`, `IBMPlexSansArabic-SemiBold.ttf`,
`Amiri-Regular.ttf`, all SIL OFL 1.1, already in the repo). That is squarely this ticket's problem,
and it may be a large part of why the UI reads as plain. AD-012 fork 2's ruling applies: Plex for
anything setting Arabic beside Latin digits, Amiri display-only.

---

## Added 2026-09-13, after tickets 04, 05, 07, 09 and 10 resolved

**This ticket's blocker (04) is resolved, so it is READY.** But its Question section above was
written before tickets 09 and 10 existed, and one of them changes what is being designed:

- **Ticket 10 turns the profile detail screen from a read-only record into a FORM.** Every field is
  editable by an operator, at any status before `approved`, because the branch runs the whole
  process during a customer visit. Designing this screen as read-only and retrofitting editing
  afterwards is exactly the rework this ticket exists to prevent. The Question's list of "known
  additions landing on top of it" does not include this, because ticket 10 was filed the same day.
- **Fields differing from their verified value must render as `overridden`** (ticket 10 decision
  3), on screen AND on the print.
- **Ticket 07's admin screen** is now a full user-management surface (list, create, disable,
  re-enable, change role, reset password) showing no customer data — more than the Question assumed.
- **Ticket 04 settled the print's content**: all 54 fields of `field-provenance.md`, both values on
  the 16 dual-source rows, both portraits and the signature, no document scans, «غير متاح» never a
  blank, a per-field manual marker, and one footer line of list versions.

  > **SUPERSEDED 2026-09-14 (S8-33).** One source per field, so no row is dual-source and no origin tag prints; no list-version footer; and the images are FIVE, document scan included. See ticket 04's round-two amendment.

- **Ticket 02 settled the image tiles**: five kinds, captioned with the image name. (**Six since S8-24** — `salary_certificate` was excluded by ticket 02's miscount rather than by decision; BL-136 closed and it is now the sixth tile.) Note that those
  captions are currently ENGLISH in an Arabic-first RTL UI — a language decision nobody has taken.
- **`BL-134` belongs in this pass**: the back office bundles no Arabic face at all
  (`index.css:19` is `font-family: system-ui`), while mobile ships IBM Plex Sans Arabic and Amiri.
  That is likely a large part of why the operator UI reads as plain.

**The print is INTERNAL — never handed to the customer** (product-owner correction, 2026-09-13),
and it ships in **TWO variants**: unattributed, and attributed with the operator who entered each
manual or overridden field named beside it. Both carry the same 54 fields; the design pass must lay
out both, and the attributed one is the denser of the two.

Question 5 (A4 or Letter) is still unanswered and still belongs to this ticket.

---

## Decision

Product-owner, 2026-09-13, by design pass. Artboards live at `Design_3/backoffice/` beside the
mobile app's own design files; they are the source, and the standalone canvas is generated from
them.

### The walk first — what is actually wrong, measured rather than guessed

This ticket's own instruction was to establish the failures before restyling. Walked the running
back office with a real browser and a real session:

- **The login carries no brand at all.** Default Ant blue `#1677ff` button, white card on a white
  page, no logo, no bank name. Brand navy appeared only in the app-shell header, AFTER sign-in —
  so the first screen a member of staff sees is an unbranded admin template.
- **No Arabic face is bundled.** Computed `font-family` is `system-ui, "Segoe UI", Tahoma, Arial,
  sans-serif` on every screen, while mobile ships IBM Plex Sans Arabic and Amiri. That is `BL-134`,
  and it is a large part of why the UI reads as plain.
- **`body` background is `rgba(0,0,0,0)`** — fully transparent. There is no page ground at all, so
  the "tinted canvas, white containers" mechanism that fixed mobile's "no visual hierarchy
  anywhere" has nothing to stand on here.
- **The profile detail page is 3,556 px — four full screens** at 1440×900. The operator's actual
  job sits behind scrolling past ten stacked description tables.
- **The list uses about a third of the screen**, default page size 10, and its six filters sit in
  one row labelled only by placeholder — so a filter loses its own name the moment it is set.
- **The list fires its fetch twice on load** (two identical `200`s), matching the
  `set-state-in-effect` lint warning at `ProfileListPage.tsx:98`.

**What is NOT broken, checked rather than assumed:** RTL is correct throughout — `dir=rtl`, sidebar
on the right, table columns right-to-left — and there is no horizontal overflow on any screen. This
ticket's question 4 worried about RTL going wrong in Ant Design; it has not.

### The decisions

1. **The operator's day is LOW VOLUME, MINUTES EACH** (product owner). Each profile is genuinely
   examined. So the **detail page is home base** and the list is only a queue: the five image tiles
   AND the decision bar sit above the fold, fields are grouped and collapsible, and the action bar
   is sticky. Rejected: optimising the list for rapid triage, which suits the opposite working day.

2. **The brand is inherited, not reinvented.** Everything comes from `Design_3/tokens/`: navy
   `#0b1c47`, steel `#5980a6`, ground `#f2f2f3`, paper `#ffffff`, IBM Plex Sans Arabic. Two rules
   there contradict Ant Design's defaults and the design follows the tokens, not Ant:
   **`--radius: 0`** ("square corners are deliberate") and **no shadows at all** ("depth comes from
   the steel glow, the pearl ring and the dune curve"). Depth is the tonal gap only.

3. **The mark is the circular pearl** (`Design_3/assets/sfb-logo-circle.png`), not the square
   master. It IS the system's one sanctioned radius, so the only round thing on screen is the one
   the system allows; and it carries no wordmark, so pairing it with the bank's name in the print
   header reads as mark-plus-name rather than name-plus-name.

4. **Export folds in here** — question 3. `BL-026`'s missing export button is on the queue design.

5. **Paper is A4** (confirmed, not assumed). 794×1123 at 96 ppi.

6. **The print header carries the logo, the bank name in Arabic, and the form title.** Not the
   branch — it is already a field in the body.

7. **Multi-page readability** — question 7: page one carries the full header; continuation pages
   carry a compact one (26 px mark, title, customer name, reference number) plus «تابع من الصفحة
   السابقة», and every page carries «صفحة N من M» with the reference-list versions footer.

8. **Static mockups, not a clickable prototype.** The brief named concrete deliverables, so the
   canvas answers what the screens should look like rather than how they behave.

### Two defects the design pass found in its own output, worth carrying into the build

- **A leading `+` on a phone number is dragged to the wrong end by RTL** — an E.164 number renders
  with its leading `+` at the far end. This is the same bug `ProfileDetailPage` already fixed with `<bdi>`,
  and it bites again in the PRINT renderer, where there is no React helper to lean on. It is
  exactly what this ticket's question 4 asked about. Fixed in the artboards with `<bdi>`.
(A third constraint — `app.artifact_ref`'s `UNIQUE (cycle_id, kind)`, which forces the print row to
be profile-keyed with `cycle_id` NULL — shaped this design but was found at review in the PREVIOUS
session and already sits on **ticket 05 decision 2**. It is not a finding of this pass.)

### Three departures from settled decisions, recorded rather than left implicit

Found at review. The artboards are **layout demonstrations**; where one departs from a binding
decision, the DECISION governs the renderer, not the artboard.

- **Section order.** `PrintPage2` groups occupation, monthly expenses and income source after the
  home address, for layout balance. `field-provenance.md` puts fields 17–20 inside **Social status**,
  before Birth data, and ticket 04 decision 1 binds the print to that order. The renderer follows
  field-provenance; the artboard does not demonstrate the order.
- **The dual-source rule is demonstrated once, not sixteen times.** *(Moot since 2026-09-14: there
  are no dual-source rows any more — ticket 04's round-two amendment gives every field one source,
  so the artboards and the renderer agree here by accident rather than by design.)* Ticket 04
  decision 2 binds 16
  field rows to print both values tagged by origin; the artboards show it on the Arabic full name and
  on the home address, and print the other dual-source rows as single values so the page stays
  readable as a sample. **One of those is a genuine substitution worth deciding:** the seven
  home-address rows are shown as ONE composite «العنوان» row, customer entry against registry text,
  because the registry returns an undecomposed string. That may be the better answer — but it is a
  change to decision 2 and belongs back on ticket 04 if adopted.
- **No viewer variant of the detail screen is drawn.** Ticket 06's ladder and ticket 05 decision 6
  say a viewer may not print and may not edit, yet the detail artboard shows the print menu and the
  inline edit controls with no viewer state. The viewer's screen is the same one minus the print
  action, the edit affordances and the decision bar.

### Still open, deliberately — now CLOSED

The five image tile captions were drawn ENGLISH here (`ID Document`, `Document Photo`, `CR`,
`Liveness`, `Signature`) in an Arabic-first RTL UI — consistent with the backend's own system-origin
`label`, but never put to the product owner as a language decision. Carried from ticket 02's closure.

**DECIDED 2026-09-13 at S8-23, when the real tiles were built: ARABIC.** Put to the product owner as a language decision, which is what both this ticket and ticket 08 said had never happened. The five captions are «وثيقة الهوية», «صورة الوثيقة», «السجل المدني», «إثبات الحياة» and «التوقيع», in `backoffice/src/profiles/artifactTiles.ts`. The defence for English was that it matched the backend's system-origin `ArtifactRefView.label`; that label is no longer rendered on any screen, so the consistency argument it rested on is gone as well.

The artboard at `Design_3/backoffice/Main.dc.html` still carries the English captions and is not
re-cut for this; it is a layout demonstration, and this section is the record of what the renderer
does — the same relationship the three departures above already describe.
