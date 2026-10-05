# 2026-09-15 — Journey assessment, AD-020, and the match-table design sample

Product-owner session. No tier was touched: the only files changed are `PROJECT_PLAN.md`,
`RISKS.md`, `BACKLOG.md` and this report. **No gates were run, and none were required** — there
is no Java, Dart or TypeScript in this diff. Two commits: the first carried an amendment to
R-042 that the second retracts — see the correction section, which is the substantive finding
of this session rather than bookkeeping.

## What was asked

1. Assess the mobile and back-office journeys and name the five largest improvements.
2. Verify that an approve/reject decision actually reaches the customer on verified channels.
3. Design a Civil Registry / scanned-document comparison table as a sample to judge.
4. Record per-channel availability as an approved decision, **without building it**.

## The five, ranked — evidence, not impressions

| # | Finding | Evidence in source | Tracked as |
|---|---|---|---|
| 1 | No "check your answers" before an irreversible submit, and no route back to stages 3–7 after stage 7. | `stage12_screen.dart:225-285` lists step NAMES and states it lists no entered value. `stage8_screen.dart` returns to `/stage-7` only; stages 9–12 have no back route at all. | BL-087 |
| 2 | The review screen does not support the decision it exists for: 63 field rows in one flat scroll, scan block at `ProfileDetailPage.tsx:560-593` and registry block at `:636-676` carrying the same facts ~80 lines apart, never paired. REJ-02 is an eyeball diff. | Read in full. | new — this session's sample |
| 3 | No correction loop anywhere: customer cannot re-enter, operator cannot edit a customer-entered field, rejected customer is told no reason. One bad character costs a branch visit. | `outcome_screens.dart` RejectedScreen ships reasonless by design. | BL-135, BL-005 |
| 4 | Export is built server-side with **zero callers** in `backoffice/`; no dashboard; list filters/page/sort are `useState` with no URL params, so "رجوع إلى القائمة" (`ProfileDetailPage.tsx:346`) discards them; no default `submitted` filter. | `ExportController` + `grep` for `/export` in `backoffice/src` returns nothing. | BL-026, BL-001 |
| 5 | The app offers channels the deployment cannot verify. | `MessageSenderConfiguration` — SMS is the only real adapter; `contact_channels_screen.dart:47` offers three. | BL-086 → now AD-020 |
| 5b | **Worse than #5 as first reported:** email is enabled against the stub on the deployed profile, so its Stage 2 row renders a live code field that can never be satisfied. Found after the assessment was delivered; see the correction below. | `application-aws.properties:73` with no `email.enabled` line; `ChannelSelection.stateFor(true, true)` → `UNVERIFIED`. | BL-149 |

## Notification chain — verified, and it holds

Traced from the operator action to the wire, not from the spec.

- `OperatorReviewService.approve` and `.reject` each enqueue to `app.notification_outbox`
  **inside the same transaction** as the guarded status `UPDATE`, its history row and its audit
  event — so a status change that is not notified cannot commit.
- Recipients are `ChannelState.VERIFIED` only; the destination is the email address for
  `EMAIL` and the phone number otherwise.
- `ManualCompletionService:185-192` does the same, reusing `SubmissionMessageRenderer`.
  `SubmissionService:271` does it for the submission notification. Three transitions, three
  enqueues; no silent state change was found.
- `OutboxDispatchScheduler` drains every 30 s (`@EnableScheduling` on `BackendApplication`),
  through `AirtelSmsSender` where SMS is `provider=http`.
- A rejection's customer-facing text is the reference list's `extraJson.customerMessageAr`,
  rendered per channel by `ReviewMessageRenderer`.

**Answer: yes.** Two caveats, both pre-existing and neither introduced here:

1. The Arabic rejection copy in `V0019__seed_rejection_reason.sql` is a translation made for
   that seed, not supplied by the bank — its own header says so. That text is what a rejected
   customer receives by SMS today. BL-005.
2. **Email cannot be verified on the profile we deploy, so it is never notified either.**
   `fru.messaging.<channel>.enabled` defaults to true when unset
   (`MessageSenderConfiguration.isEnabled`), and `application-aws.properties` carries no
   `email.enabled` line, so email is enabled against `StubMessageSender`. It therefore never
   reaches `VERIFIED`, and the `VERIFIED`-only enqueue above skips it every time. This was
   first written up here as hypothetical config hardening; it is neither hypothetical nor
   only config. See the correction below and BL-149.

## AD-020 — approved, deliberately not built

Per-channel availability becomes an additive field on the Stage 1b contract (or AD-002f's
manifest), and the app offers only channels the deployment can verify. **The product owner
approved the decision and explicitly deferred the implementation**; the build stays BL-086.

### A correction made, then retracted, inside the same session

While recording AD-020 I amended R-042 to say its stated impact — a customer "sits watching a
timer for a message that will never arrive" — **was not reachable**, reasoning from
`EntryRepository.pendingVerificationChannels` (`entry_repository.dart:232`), which filters
declined channels out of Stage 2. That amendment was wrong and has been reverted in place.

The filter covers a channel turned OFF. It does not cover a channel left ON against the stub
provider, and email is in exactly that state on the profile we deploy:

- `application-aws.properties:73` declares `fru.messaging.email.provider`, and the file carries
  **no `fru.messaging.email.enabled` line at all**.
- `MessageSenderConfiguration.isEnabled` defaults to `true` when the property is unset.
- So `ChannelSelection.stateFor(true, true)` returns `UNVERIFIED`, not `DECLINED`: a real
  `otp_challenge` row is minted and `StubMessageSender` swallows the send.
- The email row therefore renders on Stage 2 with a live code field and a working resend timer,
  and can never be verified.

It compounds into question 2's answer: an unverifiable channel is never `VERIFIED`, so the
submission, approve and reject notifications never reach it either.

WhatsApp has the same shape whenever `FRU_MESSAGING_WHATSAPP_ENABLED` is absent —
`application-aws.properties:92` defaults it to `true`, and BL-097 records that variable being
dropped by the redeploy script once already.

Filed as BL-149 with two non-alternative fixes: name every channel's `enabled` explicitly in
the AWS profile (available today, independent of AD-020), and refuse at startup to enable a
non-SMS channel whose provider is `stub`. R-042 and AD-020's rationale were both corrected.

What was found by what: the evidence came from a background config search started early in the
session that completed only after the assessment had been reported. The lesson is not about the
grep — it is that the first amendment generalised from one code path (`pendingVerificationChannels`)
to a claim about deployed behaviour without reading the deployed profile.

## The design sample

Published as an Artifact. Built in `Design_3`'s existing language rather than a new one —
`tokens/colors.css` verbatim, IBM Plex Sans Arabic with IBM Plex Sans Condensed for Latin and
digits, flat hairline borders, no radius, no shadow, matching `Design_3/backoffice/Main.dc.html`.

Design decisions worth keeping:

- **Five rows, not sixty-three.** Only fields 5, 6, 7, 9 and 21 of `field-provenance.md` are
  claimed by both sources. Single-source fields sit below the table as evidence, unpaired.
- **The national number is shown as the join key, not as a match.** The registry echoes the
  value we sent it as Uqudo's `identityNumber` (AD-002b), so a green "match" there would be
  true by construction and would be evidence the operator has not got.
- **Token-level name alignment.** The registry returns four name parts, Uqudo one string
  (field 5). A missing great-grandfather highlights as one segment, not a whole-name mismatch.
- **Amber, not red.** A difference prompts a look; it does not adjudicate. Red stays with the
  reject action.
- **Dates parsed before comparison** — the registry sends `DD/MM/YYYY`, never ISO-8601, so a
  formatting difference must never surface as a conflict.

No backend work: every field is already in `GET /api/v1/operator/profiles/{id}`.

## Not done

- No code was written. Items 1–4 of the assessment remain unbuilt and carry their existing
  backlog rows.
- AD-020 is recorded only. BL-086 is unchanged in scope.

## Parked — channel availability

**Product-owner decision, 2026-09-15: AD-020, BL-086 and BL-149 are parked behind the
back-office match-table redesign and are not to be re-raised until it ships.** Everything
found is written down above and in those rows; nothing needs re-deriving when it is picked
up. The near-term config guard named in BL-149 (naming every channel's `enabled` explicitly
in `application-aws.properties`) was offered and not taken, and is parked with the rest.

## Design sample v2 — one table

Revised on the same day after review. What changed, and why:

- **One table, not a table plus two key/value blocks.** Single-source fields join the same
  column grid under a spanning label row, so the two vertical tracks never shift between the
  compared rows and the rest.
- **Two absences, deliberately unalike.** «—» means *this source does not carry this field at
  all* — the registry has no nationality field (field-provenance field 4), the document has no
  maternal chain (field 8). «لم يرد» in amber means *this source normally supplies it and did
  not*, the real case being field 6's English name, absent from the older national-ID card
  version. Collapsing the two into one blank is how a failed registry call gets read as a field
  nobody expected. A legend states both above the table.
- The status column gains `مصدر واحد` and `لم يرد من المستند` alongside match/differ/key.

**Real-profile request declined, with the shape kept.** The sample was asked to be built from
`SFB-000000011` (Alaa Waleed). That is a real person's national number, maternal chain and date
of birth, and an Artifact publishes to an external service — a stronger exposure than the repo
rule already forbids, so the record is not in the page. The sample instead reproduces that
profile's *shape*: a four-part registry name against a shorter document string, an older card
with no English name, and a registry carrying no nationality. Judging the design against the
real record belongs in the built component, locally, where the data does not move.

## Design sample v3 — one flat table, and the document's own name

Second revision, from product-owner review of v2. Five changes:

- **The second column is named after the document the customer presented**, not «المستند
  الممسوح» and never the vendor. «البطاقة الوطنية» or «جواز السفر», from
  `DOCUMENT_TYPE_LABELS_AR` — the wording the rest of the solution already uses. Checked at
  source: the code says **بطاقة وطنية**, not بطاقة قومية.
- **The section rows are gone.** One flat table. The status column already says whether a field
  is single-source by nature (`مصدر واحد`) or absent from one side (`غير متوفر`), so a spanning
  divider was restating in layout what a column already carried.
- **«لم يرد» → «غير متوفر».** Plainer, and it reads as a property of the field rather than as a
  failed delivery.
- **The reason names the differing part and stops.** «الاسم الرابع غير موجود», «الاختلاف في
  السنة». No «الجزء الرابع», and no speculation — v2 carried «رقمان متبادلان — يُرجَّح خطأ
  قراءة», which is the table telling the operator what to conclude. A guess printed beside the
  evidence becomes the operator's starting assumption instead of their finding.
- **Dates render as segmented values** so the differing component alone can be marked, which is
  what made "name only the part" expressible for a date as well as for a name.

The one-line reason moved from the value cell to the status cell: a mismatch belongs to the
row, not to one side of it.

## BL-150 — filed, not designed

The requested next step — per-conflict resolution actions, an approvable/needs-resolution
profile state, an edit action on customer-entered fields, all audited — is **filed as BL-150
and explicitly not built**, because one part of it reverses a settled decision.

(d) and (e) need no decision: they are AD-015 as narrowed, already tracked as BL-135.

Four things do, and the first is blocking:

1. **A resolution action on a conflicting row collides with AD-015 as narrowed on 2026-09-14.**
   A conflict row is by definition a pair of Civil Registry and Uqudo fields, and that
   narrowing made all thirteen of them permanently read-only precisely so that "no edit can
   invent a scan, a face match or a registry record — this is the bound R-054 relies on".
   "Edit and approve" or "accept the document over the registry" is that write. The
   non-breaching shape is to record the operator's **adjudication** as a new fact beside two
   immutable source values — a judgement, not an edit — but that is the product owner's
   decision, not this session's.
2. **Readiness is a second axis, not a `status` value.** Every `status` transition notifies the
   customer on all verified channels, without exception. Opening or resolving a conflict is
   internal and must not send an SMS.
3. **"No conflict" needs a definition before it can gate a button.** Not string equality:
   `ref.ar_fold()` / `arabic_fold.dart` for orthography, four registry name parts against one
   document string, `DD/MM/YYYY` against a parsed date. Too strict and every profile needs
   resolution; too loose and it hides the mismatch REJ-02 exists for.
4. **Gating approve on resolution changes what approval means.** operator.md deliberately does
   not constrain how an operator reaches a decision. A hard gate is defensible but is an
   amendment to that document, not a UI detail.

## AD-021 — the customer's new ruling, and what it retires

Relayed from the bank late on 2026-09-15, after the match table had been designed and twice
revised. Recorded as AD-021; the screen and printed form redrawn to it as a design canvas.

**The ruling.** Registry-supplied fields are taken from the registry as they come, with no
comparison to the scanned document. The address is the one exception — it is what the
campaign exists to refresh, so it comes from the customer and the operator may correct it.
Customer-entry fields are taken from the customer and are editable, but only where the
customer typed free text.

**It is mostly a generalisation, not a reversal.** `field-provenance.md`'s 2026-09-14 (S8-33)
display ruling already said "Civil Registry only — 5, 6, 7, 9, 21" and "Customer entry only —
4, 18, 23, 43, and 35-41" for the printed form. AD-021 extends that same rule to the screen
and adds the free-text bound on editing.

**What it retires.**

- The match table is superseded. The comparison it exists to support is not wanted, so v1–v3
  of that design are a dead end. Recorded rather than quietly dropped: three revisions of work
  went into it, and the reason it died is a ruling, not a defect.
- **BL-150's conflict-resolution half dissolves with it** — no comparison, no conflicts to
  adjudicate — and with it the AD-015 collision that made BL-150 blocking. The
  readiness-state question survives.
- **AD-015 narrows a second time**: from the 36 S3 fields to the free-text subset, 14 fields
  on a Sudan-resident profile.

**Three things the ruling does not settle — filed as BL-152, not decided here.**

1. **Phone and email.** Typed as free text, so the rule as stated makes them editable — but
   both are OTP-verified, and the verified phone is the channel every notification uses,
   including the approve/reject decision. Editing must either be forbidden or must clear the
   channel's verified state. Drawn read-only pending a ruling.
2. **Typed but not free text.** Number of children and monthly expenses are digits. A
   misspelling is impossible; a wrong number is not. Drawn read-only on a strict reading.
3. **The editable set is data-dependent.** `customer.md` stage 5 makes state and locality fall
   back to free text for an address outside Sudan, so whether field 36 is editable depends on
   the profile's country, not on the field. A hardcoded list would be wrong for every non-Sudan
   address.

**One question raised on the printed form.** The form today follows the bank's own paper-form
order — nine sections ending with signature and attachments. Redrawing it to the screen's three
sections makes the two stop matching, and a branch officer comparing print to paper form finds a
different running order. Drawn to the three sections as asked, with the divergence flagged on the
canvas for a ruling.

## AZ Technology brand assets, and three back-office variants

The product owner supplied AZ Technology's letterhead and watermark and named the solution
**AZ Omni eKYC**. Three complete variants of the back office (sign-in, profile screen, both
printed pages — twelve artboards) were drawn for selection.

**Brand values are sampled from the artwork, not guessed.** Read off
`Design_3/assets/az-header.jpg` with PIL rather than eyeballed:

| Role | Value |
|---|---|
| Band red | `#E4312A` |
| Band purple | `#422774` |
| Band grey | `#ABADAC` |
| Mark red | `#C23232` |
| Wordmark ink | `#352F4A` |

Three assets are committed to `Design_3/assets/`, beside the existing SFB marks:
`az-header.jpg` and `az-watermark.jpg` are the supplied files untouched; `az-lockup.jpg` is a
crop of the header (the bilingual mark alone) with the letterhead band's slivers and
anti-aliased residue cleared, for use where the full band does not fit.

**The variants differ on one axis each, not on shade.** A — *Letterhead*: the real band across
every screen and page, strongest brand presence, costs ~280px of vertical space and the most
colour ink. B — *Indigo*: solid purple chrome with the lockup reversed on a white plate,
densest and closest to today's back office, slim band on paper so it photocopies. C — *Page*:
no dark chrome, identity carried by the lockup and a three-colour rule, watermark behind the
printed pages, most data contrast and least ornament.

**Content is identical across all three**, so the same canvas serves as the content review. It
carries every accumulated product-owner edit: no explanatory notes or stage labels,
«وثيقة الهوية» rather than «المستند», no face-match pill, verified channels only on the
printout, printed-by in the page footer, and the large centred approve/reject pair.

One change taken without being asked, and flagged on the canvas: the three leftover
«قابلة للتعديل» counters (occupation, home, work) were removed so all six sub-sections match —
the product owner had removed the fourth one and not the rest.

## Variant C chosen, and a transparent lockup

**The product owner chose variant C** — white chrome, identity carried by the lockup and a
three-colour rule drawn from the letterhead bands, watermark behind the printed pages and the
sign-in screen. A and B are kept on a second canvas page for comparison, not deleted.

**`az-lockup.png` added** (`Design_3/assets/`, 13.8 KB). Variant C puts the lockup on top of
the watermark, and a JPG cannot carry alpha, so the white ground had to come off. Method worth
recording because the naive approach fails: alpha is ramped on **luminance, only inside the
near-white band** (250 → 200), so the logo's own colours stay fully opaque and the
anti-aliased rim fades instead of leaving a white halo. Unpremultiplying from white — the
usual trick — would have made the saturated red partly transparent, since a solid `#C23232` and
a translucent red over white are indistinguishable on a white ground. Quantised to a 64-entry
palette, which is ample for flat artwork and cut it from 90 KB to 13.8 KB. Proved by
compositing on mid-grey before use.

Variant C's four artboards point at the PNG; A and B keep the white-ground JPG, so both remain
available. The product owner's own enlargement of the watermark on the C sign-in screen is
preserved.

## The supplied sign-in, and a garbled Arabic wordmark

The product owner supplied a designed sign-in screen with an asset pack
(`AZ_Omni_eKYC_login_assets`: `DESIGN-TOKENS.txt`, `index.html`, `styles.css`, `az-logo.png`,
`hero-photo.png`). Built as the chosen variant's sign-in, following the tokens as given — 50/50
split, 22px outer radius, `#4B237E` / `#351A63` / `#234B8F`, 56px inputs at 9px radius, 58px
button, 560px column, hero photo under its gradient overlay. The ID visual stays blurred and
decorative, as `DESIGN-TOKENS.txt` requires.

**`az-logo.png` was NOT used, and this needs the product owner's attention.** Its Arabic
wordmark reads **«شركة أي زد التقنواوية المحدودة»**. The company's own letterhead
(`AZ_Header.jpg`, the file supplied earlier) reads **«شركة ايه زيد تكنولوجي المحدودة»**.
«التقنواوية» is not a word, and «أي زد» is not how the letterhead spells the name — the mark
appears to have been redrawn rather than placed, mangling the Arabic. Verified by enlarging
both wordmarks side by side, not by eye at thumbnail size.

Used instead: `az-lockup-white.png` (5 KB) — a white reversal of the **real** letterhead mark,
built from `az-lockup.png`'s alpha channel so the chequer gaps stay transparent and the panel
colour shows through them. The registered name therefore stays correct on the one screen every
operator sees first.

**Revised the same day.** The first build reconstructed the promo panel from photo, gradient and
text layers, and the product owner judged it poor. They supplied their own finished panel
(`left side Login.png`), which is now used **whole**, as one image — photograph, gradient,
headline and typography exactly as they made them. `az-login-panel.jpg` (67 KB) is that file with
one change: its lockup carried the same garbled Arabic, so the logo box was painted out by
interpolating the purple gradient across it row by row — invisible, because the ground there is a
smooth diagonal wash — and the correct white mark composited back at the same size and position.
`az-login-hero.jpg` is deleted; the panel supersedes it.

**Two cross-cutting questions raised on the canvas, neither settled here.** The login tokens
give deep purple as `#4B237E`, while the purple sampled from the letterhead and used across the
screens and printed pages is `#422774` — close but not equal, and one should win system-wide.
And the supplied stylesheet sets Inter, which carries no Arabic at all; Arabic is set in IBM
Plex Sans Arabic to match the rest of the product, with Latin still taking Inter.

## Sign-in approved; a second set drawn in its language

The sign-in was approved. The screen and printed form were then redrawn in the language that
sign-in establishes, and both sets published side by side for comparison.

**What the login establishes, and what set 2 carries over:** rounded white cards on the
`#EEF0F4` ground with soft shadows and 14px radius; `#4B237E` for section headings, chips and
the primary action; the login's generous scale (15–16px rows, 19px section titles, 58px buttons
at 9px radius); bilingual pairs — Arabic with a small English companion, as the login's label
rows do; stroke icons and pill edit buttons. On paper the hairline section blocks become purple
bars with bilingual titles.

**The trade the product owner is being asked to make:** set 2 costs roughly 25% more vertical
space for the same content, and its purple section bars use more ink than the current hairline
blocks. Set 1 is denser — more rows per screen and more fields per page, which matters for a
reviewer working a queue all day.

**Content parity is checked, not assumed.** Both screens carry the same 52 field numbers and the
same edit-button count, so every visible difference between the two pages is the visual system.
The approved sign-in appears unchanged in both sets as the constant.

A and B from the first round move to a third page, marked superseded.

## Corrected: colours only, sizes untouched

The full-language redraw was rejected for growing the page. The instruction was to take the
login's **colours and nothing else**, holding every dimension. Set 2 was rebuilt that way, as a
literal colour-token substitution on the current files rather than a re-draw:

| token | role | → |
|---|---|---|
| `#352F4A` | ink, and every rule/label derived from it | `#20212A` |
| `#422774` | brand purple, and its tints | `#4B237E` |
| `#FFFFFF` | the screen's page ground only | `#EEF0F4` |

**Proved, not asserted.** With every colour token in both files replaced by a placeholder, set 1
and set 2 are byte-identical — so no size, space, radius, weight or string moved. The check runs
inside the generator and fails the build if it ever stops holding.

**Three colours deliberately left alone**, and the reason matters: `#E4312A` (letterhead red, on
the reject action and the three-colour rule) and `#ABADAC` (letterhead grey, same rule) have no
counterpart in the login palette, which contains neither a red nor a neutral grey — recolouring
them would mean inventing a value rather than adopting one. `#2F7D5D` is semantic success, not a
brand colour. Flagged on the canvas so the product owner can supply values if they want them
moved.

The rejected full-language set joins A and B on the superseded page rather than being deleted.

## Grey dropped from the rule; watermark held

`#ABADAC` is gone from set 2. The three-colour rule is now red and purple only, with the purple
taking the grey's width so the rule keeps its proportions rather than leaving a gap. Four
occurrences: two on the screen (header and action bar), one on each printed page.

The printed pages keep the AZ watermark. That was asserted in the edit itself rather than
checked afterwards — the script counts `az-watermark.jpg` before and after and fails if the
count moves, alongside an assertion that no grey survives. Both printed pages still carry it.

Of the three colours previously left alone, two remain: `#E4312A`, now the only accent in the
rule, and `#2F7D5D` for semantic success. The login palette still supplies no red, so that one
needs a value from the product owner if it is to move.
