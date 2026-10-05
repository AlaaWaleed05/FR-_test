# Customer journey — Fr_user_update

Specification of what the customer experiences: screen shape, branches, and terminal
states. **All policy values are settled — see the Policy values section at the end.**

Context: this solution serves a one-time government-mandated re-verification campaign.
Every account completes the update once. Repeat updates are not a foreseen use case, and
the journey is designed on that basis.

**Status: all stages agreed and rebuilt against the bank's paper form
(استمارة بيانات تحديث العملاء). Operator journey documented separately in `operator.md`.**

---

## Stage 0 — Launch check

Runs every time the app opens, before any screen is shown.

1. The app reads local storage for an in-progress session.
2. **If local state exists**, the app asks the backend for that session's current status
   before rendering anything. The backend's answer is authoritative:
   - *In progress* → **resume directly at the recorded step. No re-authentication, no
     review, no rescan.** The session was authenticated when it began and device
     continuity is itself the evidence. See Stage 13.
   - *Complete* → terminal "already completed" screen, and local state is cleared. This
     covers an account completed at a branch and marked so by an operator while the app
     was closed.
   - *Blocked until [time]* → the blocked screen for the relevant step.
3. **If the backend is unreachable**, the app resumes from local state and reconciles at
   the first opportunity. Backend-dependent steps are unavailable until then; screens
   that work offline remain available. See Stage 13.
4. **If no local state exists** → Stage 1a. A reinstall, a new device, or cleared storage
   all arrive here.

### Abandoning a session

Because one device may serve several accounts, the customer must be able to abandon an
in-progress session and return the app to a fresh start without completing it. This is
available from the resume screen and clears local state for that session.

The backend retains the incomplete profile — abandoning on the device does not delete the
server-side record. It remains resumable through Stage 1a, subject to the review rules
below, and to the retention rule. **90 days from last activity** — provisional, pending OQ-001

---

## Stage 1 — Entry and account check

### 1a. Account identification

Fields:
- Branch — selection from the bank's branch list
- Account number — entry

Action: **Next** → the backend calls the bank middleware's `CheckAccount` service over
HTTPS/JSON with the **account number only** (PROJECT_PLAN.md AD-007, OQ-024, OQ-025;
built at S3-02, 2026-09-04). The branch is kept as profile data and is not part of the
check. The middleware answers with one of three codes: `1` found, `0` not found,
`-1` system error — a set closed by product-owner decision; anything else is treated
as a contract defect and fails closed. The branch is recorded on the profile, refreshed to
the customer's latest selection on a re-entry through 1b, and is never part of the account's
identity — the account number alone is (BL-032, V0061).

No session exists yet. No profile is created and nothing is written to the profile
database at this stage, on the device or in the backend. **The attempt itself is
recorded in the audit trail** — branch, account number, result code and outcome — which
is a separate store with a separate lifetime; see the audit section below, which requires
this precisely because no profile is created.

#### Outcomes

**`0` Account not found** (wire outcome `INVALID`, continuation `RETRY`)
Return to 1a with the account number field in an error state and a message that the
account could not be found. The customer can correct and retry. No profile record is
created.

**`-1` System error, or no usable answer** (HTTP 503, no outcome)
The middleware reported its own error, did not answer in time, or answered with something
unreadable. Not something the customer can correct: the app treats it exactly like an
unreachable backend — the entry screen shows "could not reach the server, try again" and the
customer retries; at launch the app resumes offline. The attempt is still audited, with the raw reply
when there was one. (The former `2` inactive outcome no longer exists: the middleware
cannot report it.)

**`1` Account found** (wire outcome `ACTIVE`) — the backend then checks the profile database for this account:

- **No profile exists** → continue to 1b.
- **A complete profile exists** — whether completed through the app or, for profiles created
  before 2026-09-16, marked manually complete by a bank operator (**AD-022 removed that action;
  every profile created since is a mobile submission**) → terminal. The customer is told the profile update for
  this account has already been completed successfully. There is no re-entry, no
  supersede path, and no way to start again. This is deliberate: the campaign requires
  one update per account.
- **An incomplete profile exists** → the customer continues through 1b and **full OTP
  verification**, then enters the review flow described below. This branch is reached
  only when the device has no local state, since Stage 0 handles the case where it does.

### 1b. Contact channels

Reached only for an active account.

Fields:
- Phone number — entry
- **SMS** and **WhatsApp** — each independently selectable; at least one must remain
  selected.
  - **AMENDED 2026-09-08 (S8-05): SMS is selected by default, WhatsApp is NOT.** V1 is
    SMS-only, so a tester who left WhatsApp ticked selected a channel whose challenge nothing
    could deliver, and ended every walk with a permanently unverified row plus an extra
    confirmation dialog. The customer may still tick WhatsApp themselves — this is a default,
    not a restriction, and is deliberately NARROWER than [[BL-086]]/D9.1's visible-but-disabled
    row, which stays fenced behind R-042 because the app has no way to read which channels a
    deployment can actually verify.
  - ~~Owed on the BACKEND side and not changed here: `ContactChannelsRequest`'s Javadoc still
    describes both as selected by default, and would now contradict the app.~~ **DONE 2026-09-08
    (S8-07).** That Javadoc now states SMS defaults selected and WhatsApp does not. No backend
    correctness change was owed or made: mobile declares `required bool whatsapp` and always writes
    the key, so the wire default `whatsapp == null || whatsapp` is unreachable from the shipped app
    and is retained only for a pre-flip client. **Still owed, and infrastructure rather than code:**
    the `fru.messaging.whatsapp.enabled=false` staging flip, without which staging still mints a
    WhatsApp challenge for anyone who ticks the box.
- **Email address** — optional entry. The email channel row activates once an address is
  entered, and is deselectable once active.

**The at-least-one-phone-channel rule is presented as a requirement, not as an error.**
The two phone channels sit in a group whose header states the requirement plainly —
*choose at least one way to receive your code*. If the customer deselects both, the group
enters an error state under that same header and **Next** is disabled. The message
occupies the position the requirement already held; nothing new appears.

**The consequence of the current selection is stated plainly and updates live:**

> Your profile will store: SMS, WhatsApp, email

> Your profile will store: SMS. WhatsApp and email won't be saved.

Deselecting a channel is a legitimate act — a customer without WhatsApp is doing the
right thing — so this must not read as a mistake.

Action: **Next** → the session is created and a **separate code is sent to each selected
channel**. Three selected channels means three distinct codes.

The session is created here, not at 1a, so no session exists for an account that failed
the core-bank check.

---

## Resuming without local state — the review flow

Reached from 1a when an incomplete profile exists and the device holds no state:
a reinstall, a new device, cleared storage, or a session abandoned earlier on a shared
device.

### Why this flow exists

OTP verification at 1b does **not** prove the person resuming is the person who started.
The code is sent to a phone number the customer types in at that moment, so verification
proves control of a handset and nothing more. That is sufficient for a fresh start, where
the session begins empty. It is not sufficient to hand over a half-finished profile
belonging to someone else.

Two rules follow.

### Customer-entered data is restored, but must be reviewed

Contact details, social status, birth data, home address, work address and occupation, and
identity type are restored from the backend and presented for **explicit review** before the
journey continues.

The review screen states plainly that these details were entered earlier for this
account and that the customer is responsible for what is submitted. Each section is
editable in place.

**Discard and start over** is offered prominently alongside review. A customer who finds
another person's data, or who would rather re-enter their own, can clear the
customer-entered fields and begin from Stage 1b's data-entry stages. This is what makes
the shared-device case work.

### System-derived identity artifacts are not inherited

The document scan result, the extracted document data, the Civil Registry data, and the
liveness result are **not** carried into a device-less resume. The resume point is capped
at **identity type**, and the customer rescans.

These steps prove that a physical person was present with a physical document. A resume
on an unknown device breaks that chain, and rescanning re-establishes it. It costs the
customer one scan and closes the case where someone accepts a stranger's identity data on
a review screen without ever holding a document.

The prior artifacts are retained in the backend, marked **superseded**, so the audit trail
records that a scan occurred and was replaced.

### Verified channels

The fresh OTP verification at 1b **overwrites** the recorded channel states entirely. A
customer who has reinstalled may have lost access to channels verified earlier — that loss
is often the reason for the reinstall — so carrying forward a channel they can no longer
reach would leave the bank with a contact route that silently fails.

---

## Notes on Stage 1

- Three channels are verifiable: **SMS**, **WhatsApp**, **email**. SMS and WhatsApp share
  one phone number but are proven independently — a single code sent to both would prove
  neither.
- Email is optional throughout and is never required to proceed.
- **One device may serve several accounts.** Local state is cleared on completion and on
  explicit abandonment, so the app returns to a fresh start for the next customer. This is
  a privacy requirement, not only a storage one: uncleared state would expose one
  customer's identity data to the next person using the device.
- The branch list is **definitive** — 25 branches numbered 2 to 26, no branch 1. See
  `branches.md`. The app displays the Arabic name and sends the number.
- The country/state/locality hierarchy is supplied as an interim dataset with known gaps.
  See stage 4.

---

## Stage 2 — Channel verification

One screen. It shows a row for each channel selected at 1b — no more, no fewer. A
customer who deselected WhatsApp never sees a WhatsApp row.

### Each channel row

- Channel name and icon, visually distinct from the others
- The destination, masked — `•••• 4821`, `a•••@gmail.com`
- Its own code input
- Its own resend control, unlocking after **30s, then 60s, then 120s** — see Policy values
- Its own state, always visible: *verified — will be saved to your profile* /
  *not verified — won't be saved*

The rows must be unmistakable from one another. Three six-digit codes arriving at once
are interchangeable-looking, and the delivered message should name its own channel so a
customer can tell which code belongs where. A code entered in the wrong row simply fails
that row's check and can be retried — it never silently verifies the wrong channel.

Codes are distinct per channel. **5 minutes** — see Policy values

### Proceeding

**Next** is labelled *Next* throughout and never changes its label or meaning. It enables
the moment **at least one phone channel — SMS or WhatsApp — is verified**. Email alone
does not open the journey: it is the weakest possession proof of the three, and this
session unlocks a full identity-data update.

Below **Next**, a line reflecting live state, updating as rows complete:

> SMS verified. WhatsApp and email not yet verified and won't be saved to your profile.

**On tapping Next with any selected channel still unverified**, a confirmation appears —
once, naming specifics:

> **WhatsApp and email not verified**
> Only SMS will be saved to your profile. The bank will use it to contact you about this
> account.
> WhatsApp and email can't be added later.
> — *Go back and verify* / *Continue with SMS only*

*Go back and verify* reads first, so continuing is a deliberate act. **No confirmation
appears when every selected channel is verified** — a dialog on the happy path only
teaches people to dismiss dialogs.

### Corrections

- **Email address mistyped** → editable in place on its row, then resend. A typo
  otherwise leaves the customer watching a timer for a code that will never arrive.
  - **BUILT 2026-09-10 (S8-10, [[BL-101]]).** The backend half shipped at S4-06
    ([[BL-012]]); the app did not send the field until now, so this rule was live and
    unimplemented for five sessions.
  - **Editing the address and resending are two separate acts, and the first happens even
    when the second is refused.** The correction is applied inside the same transaction that
    decides the resend, before that outcome is known, and the old address's live code is
    invalidated there. So a *too soon* or *resends exhausted* answer still means the address
    changed and the previous code is dead. The screen must therefore distinguish "corrected
    and sent" from "corrected, nothing sent yet" — during a resend countdown the action reads
    *save the new email only*, never *and resend*.
  - **Consequence, and the reason the control is not always offered ([[BL-098]]):** correcting
    the address once resends are exhausted destroys the last usable code with no way to obtain
    another, leaving the email channel unverifiable for the session. The edit control is
    therefore offered only while a resend is still obtainable — it stays available during a
    countdown, since correcting then is legitimate, and is withheld on an exhausted, locked or
    already-verified row.
  - **Known gap, [[BL-103]]:** that guarantee holds within one run of the screen only. Resend
    and lock state are not persisted, so after the app is killed and resumed a capped row is
    offered the control again, and saving there destroys the last usable code. Closing it needs
    the backend to report per-channel resend state when the screen loads; the app must not
    infer it.
- **Phone number wrong** → **Back** to 1b, **but only until a phone channel verifies.**
  Changing the number changes what the session authenticates against, so it is a deliberate
  return, not an in-place edit. Channel selections may also be changed there.
  - **AMENDED 2026-09-08 (S8-05), a recorded reversal of the rule as originally written.**
    The correction link disappears the moment SMS or WhatsApp verifies. A number that has
    demonstrably received and returned a code is settled — it has authenticated the session,
    and reopening it afterwards would let the customer move the session onto a number that
    proved nothing. Product-owner decision taken 2026-09-07 during the guided walk (comment
    5d, filed as [[BL-091]] item 1) after a device photograph confirmed the link was showing
    beside an already-verified SMS row. Before this, the repair path was unconditional.
    The email correction above is unaffected — it is an in-place edit, not a session change.

### Failure paths

- **Wrong code entered **5** times on a channel** → that channel
  locks for the session. Its state becomes *not verified — won't be saved*, and it cannot
  be retried.
- **Resends exhausted on a channel** — **3 per channel per session** → that channel's resend
  control is disabled. Any code already delivered remains usable until it expires.
- **Both phone channels locked, or the only selected phone channel locked** → terminal.
  No phone channel can be verified, so the session cannot be authenticated. The customer
  is told to try again later or visit a branch. **15 minutes, escalating to 1 hour**
- Email locking has no effect on progression. The journey continues without it.

### What is recorded

Per-channel verification state is **system-derived**: the backend owns it and the device
never overwrites it.

The profile distinguishes three outcomes per channel, because they mean different things
when the bank later tries to reach this customer:

- **verified** — proven, saved, usable for contact
- **declined** — deselected at 1b, never attempted
- **unverified** — selected and attempted, never proven

Completion notices at Stage 12 go only to channels in the **verified** state.

---

## Field provenance

The authoritative field-by-field mapping is `docs/journeys/field-provenance.md`. It covers all
54 fields of the bank's paper form with the source decided for each.

Precedence: **Civil Registry, then Uqudo, then customer entry.** Take from the registry where
it supplies the field; otherwise Uqudo; otherwise ask the customer. The product owner has
overridden this default on specific fields where the mechanical answer was wrong — the matrix
records each override and why.

Everything is retained regardless of what the profile shows. The full Uqudo JWS, the full raw
Civil Registry response and the full core-banking exchange are stored byte-identical in the
audit schema. **The profile is a derived view over that source data; both persist.** A field
taken from Uqudo does not discard the registry's value — that value lives in the artifact and
not on the profile.

Two labels on the bank's form mean something other than their literal reading, confirmed by
the product owner on 2026-08-23: field 9 is the bank's label for **sex**, and field 10 is the
bank's label for **ethnicity**. Both are recorded in the matrix.

---

## Stage 3 — Personal, social and birth data

First of four data-entry stages (3–6). All are **offline-capable**, written to device
storage as the customer types, and persisted to the backend per completed stage when
connectivity allows.

**Back-navigation is free across stages 3 to 6.** Nothing here has an external side effect —
no Uqudo operation, no OTP consumed, no core-bank call. The constraint arrives at stage 8.

### Fields, in order

1. **Sex** (field 9) — asked here because Arabic gendering and the marital-status branching
   both need it before any scan. **The Civil Registry value is what the profile stores**;
   the customer's answer drives the interface only.
2. **Ethnicity** (field 10) — free text, **mandatory**. No source supplies it. New field.
3. **Country of residence** (field 11) — **mandatory**, selected from the ISO 3166 country
   list (S2-07), **defaulting to Sudan**. Not supplied by any source. The default was added
   2026-09-10 by product-owner ruling from the walk test: this was the only country picker in
   the app that opened empty, while field 22 below and both address hierarchies (fields 28, 35)
   all opened on Sudan. Sudan is the market, so it is the default wherever a country is asked.
4. **الحالة الاجتماعية — marital status:** أعزب/عزباء · متزوج/ة · مطلق/ة · أرمل/ة

Then, conditional on marital status:

| Marital status | Spouse name | Has children? | Number of children |
|---|---|---|---|
| Single | — | — | — |
| Married | asked | asked | only if yes |
| Divorced | — | asked | only if yes |
| Widowed | — | asked | only if yes |

Number of children is **never asked directly**. It sits behind a yes/no, so a customer
without children answers once rather than entering zero.

5. **مستوي التعليم — education level.** A new customer-entered field the paper form
   introduces, like ethnicity and country of residence above; nothing derives it.

| # | Arabic | English |
|---|---|---|
| 1 | أمي | Illiterate |
| 2 | يقرأ ويكتب | Reads and writes |
| 3 | أساس | Basic education |
| 4 | ثانوي | Secondary |
| 5 | دبلوم / معهد فني | Diploma / technical institute |
| 6 | جامعي | University degree |
| 7 | دراسات عليا | Postgraduate |

Follows the Sudanese system — أساس، ثانوي، جامعي — so the terms match what customers
recognise. أمي and يقرأ ويكتب are separated deliberately: they are genuinely different, both
appear in Sudanese official forms, and the distinction matters for a bank that may need to
know a customer cannot read a contract.

**Ordinal, so it displays in this order, not alphabetically** — unlike occupation. Seven
values need a simple picker with no search. No أخرى: the list is exhaustive.

6. **البلد — birth country** (field 22) — **mandatory**, selected from the ISO 3166
   country list (S2-07), **defaulting to Sudan**. No source supplies it: MRZ `issuer` is the
   document's issuing country, not birth country, and reads `SDN` for every customer
   regardless of where they were actually born. Same list and alphabet as country of
   residence (field 11) and both address hierarchies (fields 28, 35).
7. **Birth state** (field 24) — **mandatory**. When birth country is Sudan, selects from the
   Sudan state list; otherwise free text. Same non-Sudan fallback pattern as the address
   hierarchy (stage 5).
8. **Birth city** (field 23) — **mandatory, free text, always asked.** The field's primary
   source is Uqudo's `placeOfBirth`, but Uqudo data does not exist until the scan at stage 8
   — at this point in the journey there is no way to know whether the customer's answer will
   even be needed. The customer therefore always answers here, for the interface; **Uqudo's
   value supersedes theirs when the scan lands**, since precedence already ranks S2 above S3
   for this field. Same shape as sex (item 1 above): the customer's answer drives the
   interface, and the authoritative source is what the profile actually stores.

### Exits

- **Next** → stage 4
- **Back** → disabled. Stage 2 is complete and its codes consumed; there is nothing to
  return to.

---

## Stage 4 — Occupation and income

Offline-capable. Free back-navigation to stage 3.

### Fields

- **المهنة — occupation.** Searchable picker over the bank's coded list. See the reference
  data section.
- **مصدر الدخل — income sources.** Multi-select, **one marked primary**. أخرى opens a
  free-text box.
- **Monthly expenses** (field 19) — free text constrained to digits, in SDG. The field
  carries a visible hint stating this constraint.

Occupation sits here rather than beside the employer at stage 6. The pairing was originally
justified by collapse logic — occupations with no employer hiding the work-address fields —
and that logic is deferred to v2, so occupation now belongs with income as the financial
picture.

### Exits

- **Next** → stage 5 · **Back** → stage 3

---

## Stage 5 — Home address

Offline-capable. Free back-navigation to stage 4.

### Fields

**Cascading pick-lists**, each populating the next:

- **البلد** — defaults to Sudan
- **الولاية** — populated by country
- **المحلية / المحافظة** — populated by state

**Free text:**

- **المدينة** · **المنطقة** · **الشارع** · **المربع** · **رقم المنزل**

Cascading lists give clean, filterable data and are faster than typing Arabic on a phone.
The same locality typed freely arrives spelled several ways and in two scripts, which makes
the operator export unusable for filtering.

### Addresses outside Sudan

Selecting a country other than Sudan leaves state and locality with no dataset behind them.
**Both fall back to free text.** Not a rare case: a customer who verified over WhatsApp but
could not receive SMS is often abroad.

### Exits

- **Next** → stage 6 · **Back** → stage 4

---

## Stage 6 — Work address and employer

Offline-capable. Free back-navigation to stage 5.

### Fields

- **جهة العمل — employer.** Free text.
- Then **البلد · الولاية · المحلية/المحافظة** as cascading pick-lists, and
  **المدينة · المنطقة · الشارع · المربع** as free text.

**No رقم المنزل** — the form carries جهة العمل in its place.

### All fields mandatory, for every occupation — v1

A student, homemaker or retiree still completes this stage. The employer field is free text,
so anyone can enter something.

This is a deliberate v1 decision, revisited when the bank supplies a cleaner occupation list
together with the logic to drive a carve-out. The cost is recorded as **R-020**: forced
selection of a work state and locality from structured pick-lists produces arbitrary values
in fields the bank filters and aggregates on.

A work address abroad is more likely than a home address abroad, so the non-Sudan free-text
fallback matters more here.

### The optional attachment

**شهادة مرتب — income or salary certificate.** Optional, and the only attachment in the
journey. Camera capture or file selection.

It is stored on the profile, validated by nothing, and **gates nothing** — no status, no
completion, no operator action depends on it. It is the heaviest payload in the journey and
**must never block completion**: if the upload fails or is still queued, the profile submits
without it and the attachment lands whenever it can.

**Stored exactly like the identity-document images and the signature** (AD-016): a row in
`app.artifact_ref` with its bytes in the database, one per profile. The difference from the
identity images is that those are keyed to an identity cycle, so a re-scan supersedes them, while
this and the signature are keyed to the profile — re-scanning a passport does not replace a
salary certificate. Uploaded at attach time and retried once on Next if that failed; the screen
confirms «تم إرفاق» only once the backend has accepted the file, never on local save alone.

**What the bank is told when no file arrives** (BL-122, closed 2026-09-13). A profile with no
certificate used to be ambiguous between a customer who declined and an upload that never
succeeded — both left no `app.artifact_ref` row, so an operator could not tell them apart. Stage 6
now reports whether a file was attached at all, and the operator view resolves four answers: the
bank holds it, the customer attached one that never arrived, the customer declined, or Stage 6
never reached the bank so the customer was never asked.

Only the ATTACHED claim is sent; declining is its absence. The customer has no way to say "no" —
this field offers capture, selection and retry, with no remove and no skip — so a decline is made
by doing nothing, and there is no affordance to assert it from. It also fails safe: a claim lost in
transit reads as "declined", which is the behaviour that shipped before, whereas a lost decline
would read as a failed upload and send an operator chasing a customer who simply said no.

**It changes nothing an operator does.** Product-owner ruling, 2026-09-13: the certificate stays
optional, still gates nothing, and a failed attachment is not a rejection reason. The back office
is informative about it and nothing more.

**10 MB; JPEG, PNG or PDF; downscaled on-device**

### Exits

- **Next** → stage 7 · **Back** → stage 5

---

## Reference data and field decisions — SETTLED

#### Occupation — bank-supplied coded list

Source: `كود_المهنة.xlsx`, 138 occupations, `JOBCODE` 1–171 with 33 gaps in the range.
The code is what is stored and sent; the label is what is displayed.

- **Arabic and English names are used exactly as supplied**, including the awkward ones
  (خياط = "Needle", محاسب = "Amenable", ربة بيت = "HOUSEWIFELY"). Not corrected. When the
  bank supplies a better list, it replaces this one without code changes — see reference
  data delivery below.
- **Three occupations appear twice under different codes.** The **smaller code wins**; the
  larger is dropped from the picker: مبرمج → 86 (not 133) · ممرض → 43 (not 139) ·
  موظف حكومة → 9 (not 135).
- **Code 33, اخرى / Other, exists** and is the landing place for anything unlisted.
- **No free-text fallback.** The customer must select from the list. This is deliberate and
  differs from income source below: occupation codes belong to the bank's own systems, and
  a free-text value would have no code for their core to consume.

**Picker design** — 138 items on a phone requires a full-screen, search-first selector, not
an inline dropdown:

- Opens with the search field focused and the keyboard already up.
- **Substring** matching, not prefix, across Arabic and English simultaneously, so "هندس"
  finds مهندس.
- **Arabic normalisation is mandatory** and is the single most important implementation
  detail here: أ إ آ ا treated as identical; ة and ه identical; ى and ي identical; tatweel
  and diacritics stripped from both the query and the list. Without it a customer typing the
  natural spelling misses the entry and concludes their occupation is absent.
- Empty search shows the full list in **Arabic alphabetical order**. Code order is
  meaningless to a customer.
- The code is never displayed; it is what gets sent.
- A no-results state suggesting a shorter word, since the usual cause is a compound term.

**All fields mandatory, including work address, for every occupation — v1.** A student or
retiree still completes the work-address block. The narrow carve-out for طالب (15),
ربة بيت (16) and متقاعد (22) was considered and **deliberately deferred**: it is revisited
when the bank supplies a cleaner occupation list, together with the logic that would drive
it.

#### Income source — short list, multi-select, with free-text other

No bank-supplied list exists and none will be requested. Deliberately shaped differently
from occupation:

راتب / أجر · أرباح عمل تجاري · معاش تقاعدي · إيجار عقار · تحويلات من الخارج ·
زراعة أو ثروة حيوانية · عوائد استثمار · مساعدة أسرية · **أخرى (free text)**

- **Multi-select.** A customer may hold several sources — a salary plus remittances is
  common.
- **One selection must be marked primary.** A KYC dataset needs a principal source; an
  unordered set does not answer it.
- **أخرى opens a free-text box.** Acceptable here, unlike occupation, because these values
  belong to this system rather than to the bank's coded core.

A short list rather than pure free text because "راتب", "مرتب", "salary" and "موظف" all mean
the same thing and would arrive as four values, leaving questions like *how many customers
live on remittances* unanswerable.

#### Provenance corrections

Superseded by `docs/journeys/field-provenance.md` (2026-08-23) — see the Field provenance
section above for the authoritative, field-by-field source for nationality, citizenship, birth
date and birth place. Ethnicity, country of residence, birth country and birth state are also
new customer-entered fields the paper form introduces (see Stage 3) — education level is no
longer the only one.

#### Reference data delivery — an architectural requirement

**No list is hardcoded.** This covers occupations, branches, administrative divisions,
income sources, and rejection reason codes. Every one of them is expected to change, and
two are known to be interim.

Hardcoding means an app release for every list update — and on an Android-first launch,
waiting on store review to correct a locality name.

This collides with offline capability: stages 3–6 must work with no connection and cannot
render a picker whose contents live on a server. The shape:

- The app **fetches the lists and caches them locally**.
- On each connection it **checks a version** and refreshes when a newer one exists.
- Offline, it serves from cache.
- **The list version used for a submission is recorded on the profile.** Locality code 4103
  under the interim dataset may not mean the same thing once the corrected dataset lands.

This must be designed in, not retrofitted — caching reference data touches the same
persistence layer as the resume model. Belongs in AD-002.


---

## Stage 7 — Identity type

The last stage before Uqudo enters, and the last with free back-navigation.

### Field

- **Identity document type** — selection: passport or national card

Nothing else. The document's own fields — number, issue date, issuing place, expiry,
issuing country — are extracted from the scan at stage 8 and are never typed.

**The Arabic label for the national card is «البطاقة القومية», not «الرقم الوطني»**
(amended 2026-09-10, walk comment 8). «الرقم الوطني» is the *number printed on the card*
— it names the document after one of its own data fields. Stage 9's review screen keeps
«الرقم الوطني» for the extracted number itself, where the term is correct.

### On Next

The app sends everything collected so far to the backend, which creates or updates the
customer record with the entered data.

**No Uqudo token is requested here.** The token is requested at the moment the customer
taps to scan, at stage 8.

The reason is the token's 1800-second lifetime. A customer who reaches this stage and then
puts the phone down for half an hour would arrive at the scan holding a dead token, and
the resulting failure looks like the app breaking. Requesting at the point of use means
the token is never more than seconds old when the SDK receives it, and removes a stale
state from the journey entirely.

The cost is one extra backend call — stage 7's Next and stage 8's scan launch are separate
round trips rather than one covering both. This is accepted.

### Exits

- **Choosing a document** → stage 8. **Amended 2026-09-10 (walk comment 4): the choice IS
  the action.** There is no longer a separate **Next** button — tapping the passport or the
  national-card option saves the choice, submits the stage and advances in one gesture.
  The earlier design had the card select and a Next button advance, on the grounds that a
  customer should be able to change their mind before committing to a document type, since
  the per-type scan budget (5 attempts, BL-039) makes a wrong choice expensive. That
  concern is answered rather than dropped: stage 8 opens on its preparation screen, which
  offers **«تغيير الوثيقة»** back to this stage, and offers it again if a scan is rejected.
  The budget is spent by *launching the scanner*, not by arriving at stage 8 — so a mis-tap
  costs no attempt.

  **What a mis-tap does cost, stated precisely rather than waved away:** the tap submits
  stage 7 to the backend and advances the resume pointer to stage 8, and «تغيير الوثيقة»
  deliberately does not move that pointer back (back-navigation never moves it anywhere —
  the choice recorded at S5-05). So a customer who mis-taps and then closes the app
  relaunches into stage 8 holding the wrong document type, not back here. It is fully
  recoverable — stage 8 opens on the preparation screen, which offers «تغيير الوثيقة» — but
  it is a recovery, not a no-op, and the enumeration above should not read as exhaustive
  without it.
- **Back** → the last of the data-collection stages. Free.
- **Backend unreachable** → the entered data is on the device and queued. The customer is
  told a connection is needed to continue, and returns when they have one. See stage 13.
  Unchanged by the above: a queued submission still holds the customer at this stage rather
  than walking them into a scan they cannot start.

### Note — this is the boundary

Before this point, every stage is offline-capable and freely revisited. After it, each
step consumes a real Uqudo operation, and back-navigation is bounded by the retry rules in
stages 8 and 9.

---

## Stage 8 — Document scan

### Preparation

A brief screen before the camera opens: which document is about to be scanned, and plain
guidance — good light, flat surface, whole document in frame, no glare. One tap to begin.

**This screen carries real weight, because cancelling out of the scan counts as an
attempt.** It is the customer's only free opportunity to get set up before the retry
budget starts being consumed, so the guidance must be genuinely useful rather than
decorative.

It sits in front of the Uqudo SDK's own instructions and must not duplicate them. What the
SDK displays, and in which language, is unknown until the device spike (S1-02, R-002), so
this screen's final content is provisional.

### The scan

On tapping to begin:

1. The app requests an enrolment token from the backend, scoped to the document type
   chosen at stage 7.
2. The app launches the Uqudo SDK with that token.
3. The SDK renders its own full-screen capture UI, handles capture and upload internally,
   and returns a **JWS compact string**.
4. The app posts that string to the backend **untouched**. It does not decode it, inspect
   it, or extract anything from it.
5. The backend verifies the signature against Uqudo's JWKS, parses the payload, downloads
   each image by ID over an authenticated call, extracts the national number, and calls
   the Civil Registry — see stage 9.

The app is in the loop because capture needs the camera. It is blind to the contents
because verification is server-side only. Both are true at once.

### Outcomes

**Scan succeeds and the backend accepts it** → stage 9.

**Scan fails — the SDK could not read the document.** The customer is shown that it did not
work and offered:
- **Try again** → back to preparation, same document type
- **Change document** → back to stage 7, where the other document type can be chosen

**Customer cancels out of the SDK** → returns to preparation, and **counts as a failed
attempt**. A launched SDK session consumes a real Uqudo operation whether or not a document
was captured, so a cancel is not free.

**Token request fails, or the JWS upload fails** → a connectivity failure, not a scan
failure, and **not** counted against the retry budget. If the SDK already returned a JWS,
**the app retains it and retries the upload** rather than making the customer rescan. See
stage 13 for the stale-token case.

**Backend rejects the JWS** — signature invalid or verification fails → counted as a failed
attempt. The customer sees a generic failure and may retry. Repeated verification failure
is a system fault, not a customer fault, and must surface to operators rather than looping
the customer.

### Retry budget

**What one attempt is.** A **launched SDK session** — one token per launch. The backend mints
a token, the app launches the SDK with it, and that token is **single-use**: it is spent the
moment the attempt fails, and a further post through the same session is refused. A retry
means a new token, and therefore a new attempt.

Not every launch costs the customer an attempt. The exempt outcomes are the ones listed under
**Outcomes** above that are explicitly not counted — a **successful scan**, a **camera
permission the customer declines**, a **token request or upload that fails on connectivity**,
and the two backend-side timing outcomes (the stored capture has expired, or its images are no
longer retrievable). None of these is a scan-quality problem, so none of them is charged.

Two counters, because they answer different questions:

- **5** — once exhausted, the customer may still switch to
  the other document type with a fresh per-type budget. A passport is a genuinely different
  attempt, not a sixth try at a failing national ID.
- **10** — bounds the whole stage regardless of switching.

**Either budget exhausted → temporary block.** The customer is told scanning is unavailable
for now, and to try later or visit a branch. **24 hours**

The block applies **to this stage only**. Everything already entered and verified stays
intact and resumable — contact channels, collected data, identity type. The customer
returns and resumes at the scan, not at the beginning. See stage 13.

### Note

The Uqudo SDK does not function on an x86_64 emulator and supports armeabi-v7a and
arm64-v8a only, so this stage cannot be exercised without a physical arm64 device
(docs/components/uqudo-sdk.md).

---

## Stage 9 — Civil Registry review

The customer sees, for the first time, what the system has established about their
identity. **Nothing on this screen is editable** — the data is authoritative by design.

### What the backend did before this screen

Triggered by the accepted JWS at stage 8, as one server-side chain the app is blind to:

1. Verified the JWS signature against Uqudo's JWKS
2. Parsed the payload
3. Downloaded the document images and the extracted portrait by ID
4. Extracted the national number
5. Queried the Civil Registry with it
6. Wrote the results to the profile
7. Returned a single display payload to the app

The app never sees a national number it has to route anywhere, and never holds partial
identity state.

### The screen

**The national number is shown first, alone and prominent**, separated from everything
else. The entire screen hinges on the customer checking that one value against the
document in their hand, so it must not sit buried in a list of fields.

Below it: the registry data, the document image, and the extracted portrait — each as its
own visually distinct section rather than one continuous run of fields.

**The screen does NOT tell the customer which authority supplied the data.** Product-owner
decision 2026-09-08 (walk comment 10b), applied at S8-05: the data-source label «بيانات
السجل المدني» and the registry portrait's «صورة السجل المدني» caption were both removed, and
this paragraph is amended with them — it previously read "the Civil Registry data", which is
what the screen used to say out loud. The customer confirms the data; they are not told where
it came from.

**One deliberate exception, and it must stay.** The terminal message for "the number is
right but my details are wrong" still names the Civil Registry, because there it is not a
label but the *reason* the customer has to visit a branch — stripped of it, "go to a branch"
loses what it is for, which is exactly what this document's own "explain rather than refuse"
rule forbids. Pinned by a test in `stage9_screen_test.dart`.

### Actions

**Accept** → stage 10.

**"The national number is wrong"** → back to stage 8 preparation. Framed as a scanning
problem, because that is what it is: the wrong number was read, so the registry returned
nothing useful or someone else's record. **Counts against the stage 8 retry budget.**

**"The number is right but my details are wrong"** → terminal, directed to a branch.

The terminal message must explain rather than refuse. The bank cannot record identity
details that differ from the Civil Registry, and the registry cannot be corrected from this
app — so the customer is told to visit any branch with their document to have it corrected.
"Go to the branch" without a reason reads as the app failing.

**Why this diagnostic question works:** the customer can actually answer it. They know their
own national number and can read it off the document in their hand. Asking instead whether
the data is "wrong" invites a judgement they cannot make — they have no way to distinguish a
misread from a stale registry record, would answer "wrong" either way, and would loop until
the retry budget stopped them.

Both failure paths converge at the branch: repeated scan failures and a confirmed registry
mismatch both end there, which is correct.

### Civil Registry unreachable or returning nothing

**The session pauses. It does not fail.** By this point the scan is complete and the backend
already holds verified identity data from the JWS, so an outage costs nothing already done.

The customer sees a distinct state — the service is unavailable, progress is intact, come
back shortly — not an error and not a failure. On resume the backend simply retries the
lookup: no rescan, no new token, no repetition of anything the customer did.

This is the only place the journey stops for a reason that is neither the customer's fault
nor a fault in this system. It is drawn as its own state for that reason. See R-011.

### Documents and the national number — RESOLVED

Both accepted document types — passport and national ID — carry the national number. A
passport old enough to lack one is necessarily expired and therefore not a valid document.
There is no population that reaches this stage without a number to look up.

**Expiry is not checked — RESOLVED.** A genuine but expired document is accepted. Only
forgery matters, and detecting it is Uqudo's job. The expiry date is extracted and stored
like every other document field, but it gates nothing in the journey.

---

## Stage 10 — Liveness and face matching

The one stage that captures the customer's face. Uqudo's face session answers **two**
questions in a single operation:

- **Liveness** — is this a live person in front of the camera, not a photograph, a screen,
  or a mask
- **Face match** — is this the same person as the portrait extracted from the scanned
  document at stage 8

The second is what actually binds the person to the document. Without it, a genuine
document held by someone else passes the journey.

### Preparation

A screen before the camera opens, following standard practice for face verification:

- What is about to happen and why — a short check confirming it is really them
- Practical guidance: good even light; face the light rather than standing with a window
  behind you; phone at eye level; remove sunglasses; no hat brim shadowing the face; be
  alone in frame
- What will be captured, and that it is stored with their profile
- One tap to begin

Like stage 8's preparation, this sits in front of the Uqudo SDK's own liveness UI and must
not duplicate it. What the SDK instructs, and in which language, is unknown until the device
spike (S1-02, R-002), so the final content is provisional.

Unlike stage 8, this screen materially affects success rates. Liveness failures are
overwhelmingly environmental — backlight, low light, angle — and a customer told to move
somewhere brighter *before* starting often passes first time.

### The check

1. The app requests a liveness token from the backend **at the moment of tapping to begin**
   — same reasoning as stage 8. The face session and its uploaded reference image are
   deleted by Uqudo after **600 seconds** — a tighter clock than the 1800-second
   enrolment token.
2. The app launches the Uqudo SDK, which renders its own full-screen liveness UI.
3. The SDK returns a **JWS compact string**.
4. The app posts it to the backend untouched.
5. The backend verifies the signature, parses, retrieves the verification detail and
   audit-trail image, and updates the profile.

### Outcomes

**Passes both liveness and face match** → stage 11.

**Fails** → the customer is shown it did not work and may **try again**, returning to
preparation. Counts against the budget.

**Cancels out of the SDK** → counts as an attempt, consistent with stage 8.

**Token or upload failure** → connectivity, not liveness. Not counted. A returned JWS is
retained and the upload retried rather than repeating the check.

**Budget exhausted** — **5** → temporary block on this stage only.
Everything before it stays intact and resumable. The customer is told to try later or visit
a branch. **24 hours**

The liveness budget should be **more generous than the scan's**: its failure causes are
environmental and correctable within a session — moving to a window fixes what a fourth
attempt in the same dark room will not.

### What the customer sees, and what the bank records, differ deliberately

The customer sees one outcome: it worked, or it did not. There is no "the check is wrong"
path — unlike stage 9, there is nothing to disagree with.

The **profile and audit trail must distinguish the two failure modes**, because they mean
opposite things:

- **Liveness failure** — environmental, the ordinary case, no fraud signal. It produces
  **no signed artifact at all**: the SDK enforces liveness internally, re-prompts within
  the launch, and after its internal attempts throws
  `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS`. There is no liveness score.
- **Face-match failure** — the person holding the phone is not the person in the document.
  This is the strongest fraud signal the journey produces, and it arrives as a
  **successful** call: a signed JWS carrying `face.match = false` and `face.matchLevel`
  (1–5).

The two failure modes therefore reach the backend through entirely different channels — a
signed artifact versus a thrown error — which makes them impossible to confuse, but only if
`setMinimumMatchLevel()` is never set on the face session. Setting it would make the SDK
consume a low match as an internal retry and terminate exactly like a liveness failure,
with no signed artifact. See AD-002a.

**No — same budget as liveness, and the customer experience must not differentiate.**

---

## Stage 11 — Signature

Mandatory. The customer signs to affirm the completed submission, matching the position of the
signature block on the bank's paper form.

### Two capture routes, both offered

- **Draw on screen** — a signature pad, with a clear-and-retry control.
- **Upload an image** — from the device's photo library or camera.

Neither is preferred; the customer chooses. Both produce a file stored on the profile.

### It is mandatory

There is no skip. A customer who can neither draw nor upload cannot complete the journey and
must go to a branch. This is a deliberate consequence of the requirement, not an oversight.

`[POLICY: signature file size and format limits]`

### Exits

- **Next** → stage 12, completion and submission
- **Back** → disabled. Liveness is complete and its artifact issued; there is nothing to return
  to.

---

## The audit trail — separate from the profile

Two distinct stores with different shapes, lifetimes, access rules and consumers.

**The profile** is mutable current state. It is what an operator reads and what the export
produces: the customer's details as they now stand.

**The audit trail** is an append-only record of what happened, when, and with what proof.
It is what a regulator, an investigator, or a dispute reads. It is never updated and never
deleted.

Conflating them loses history the moment anything is corrected, and makes the profile
table carry weight that every ordinary query then pays for.

### Principles

**Append-only.** No `UPDATE`, no `DELETE`, enforced at the database level, not by
convention. A correction is a new event, never an edit of an old one.

**Tamper-evident.** Each record carries a hash of its own content plus the hash of the
preceding record, forming a chain. Any alteration or removal breaks the chain detectably.
This is what makes the trail evidence rather than merely a log.

**Server-authoritative time.** Every event is timestamped by the backend in UTC. Device
clocks are not trusted; where a device time is relevant it is recorded as a *claimed* value
alongside the server time, explicitly marked as unverified.

**Correlated.** Every event carries the session identifier and a monotonic sequence number
within that session, so the full journey can be reconstructed in order.

**Raw artifacts preserved, not just parsed values.** Store the artifact that carries the
proof:
- The **raw Uqudo JWS** for both the document scan and the face session — the signed
  artifact, independently verifiable against Uqudo's JWKS years later. Parsed fields are
  derived data and prove nothing on their own.
- The **raw Civil Registry request and response** — the exact bytes sent and received, as `civil_registry_request` / `civil_registry_response` artifacts (S3-14) — alongside the extracted values.
- The **core-banking `CheckAccount` request and response** — the exact bytes sent and received, as `omni_check_request` / `omni_check_response` artifacts — with its result code (S3-02).

**Never re-encoded.** Images and signed artifacts are stored byte-identical to what was
received. An altered image is worthless as evidence.

**Audit access is itself audited.** Reading the trail is an event.

### Events recorded — customer side

- Account check attempted: branch, account number, result code, outcome. **Recorded even
  when no profile is created**, since invalid and inactive attempts are exactly what a
  misuse investigation needs.
- Session created.
- Per channel: code issued, delivery result, verification attempt, verification outcome,
  resend, lockout.
- Each data stage submitted.
- Identity type selected, and each change of it.
- Uqudo token issued — scan and face session separately.
- Scan attempt: started, cancelled, failed, succeeded — with which document type.
- JWS received; signature verification result; images retrieved.
- National number extracted; Civil Registry queried; raw response; outcome.
- Stage 9 action taken: accepted, number-wrong, details-wrong.
- Face session: recorded as TWO distinct event types, because the two failure modes reach
  the backend through different channels.
  - `face_match_evaluated` — a signed JWS was returned. Records `face.match` (bool),
    `face.matchLevel` (1–5), and the server-side threshold applied.
  - `liveness_attempt_terminated` — no JWS was produced. Records the SDK error code
    (`SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS`) and the attempt number.
  **There is no liveness score. Uqudo does not return one.** See AD-002a.
- Blocks applied and expired, with the reason.
- Completion; device state cleared.
- Abandonment.
- Resume: reconciliation performed, fields overwritten, artifacts superseded.
- Notification dispatched per channel, with delivery result.

### Events recorded — operator side

- Sign-in, sign-out, failed sign-in.
- **Profile viewed** — which operator, which profile, when. Identity data access is the
  point of this record.
- **Document image or portrait viewed** — recorded separately from viewing the profile,
  because it is the most sensitive access in the system.
- Search and filter executed.
- **Export performed** — the operator, the filters applied, the row count, and the fields
  included. This is bulk PII leaving the system and is the highest-value audit record the
  operator side produces.
- ~~**Manual completion** — the operator, the profile, the time, and the recorded
  justification.~~ **NO LONGER EMITTED — AD-022 (2026-09-16) removed the action. Events
  recorded before that date remain in the append-only trail.**
- Access-level and account changes.

### Retention

The audit trail outlives the profile. Where an abandoned profile is deleted under the
retention rule, the audit record of what happened to it remains.
**7 years**

---

## Stage 12 — Completion and submission

The end of the **customer's journey**, but not the end of the **profile's life**. The
profile is submitted to the bank for review; the outcome arrives later. These are now two
distinct events and must not be conflated in the interface or in the record.

### Ordering — strict

1. Liveness JWS accepted and the profile updated (stage 10)
2. Signature captured and stored on the profile (stage 11)
3. Backend sets status to **`submitted`** and assigns a **reference number**
4. Backend confirms to the app
5. **Only then** does the app clear local storage for this session
6. Backend dispatches the submission notification

A connection drop between steps 1 and 3 means the app holds its state and reconciles on
resume. It never assumes completion. See stage 13.

### The confirmation screen

- The update is **complete and submitted to the bank for review and approval**. Not
  "approved", not "done" — the customer must not leave believing the matter is closed.
- The **reference number**, prominently. It is the customer's only artifact once local
  storage clears, and it is what they quote at a branch if the profile is later rejected.
- Which channels were verified and will carry the decision.
- That they will be notified of the outcome.

The optional salary certificate, if still queued, does not hold this screen back. The
profile submits without it and the upload lands whenever it can.

### Status model

| Status | Meaning |
|---|---|
| `submitted` | Customer journey complete, awaiting operator review |
| `approved` | Reviewed and accepted. Final. |
| `rejected` | Reviewed and refused, with a recorded reason. Resolution is at a branch. |

A rejected profile **can later be approved** once the customer resolves the matter at a
branch. The transition `rejected → approved` is a legitimate operator action.

### Every status transition — the governing rule

Any change of status, without exception:

1. Is written to the profile database as the new current state
2. Is recorded in the audit trail as an event carrying the **actor**, the **timestamp**,
   the **prior and new state**, and the **reason** where one applies
3. Is **communicated to the customer on all verified channels**

There are no silent state changes.

**Status history is retained and readable, not only the current status.** A profile that
went `submitted → rejected → approved` was resolved by a human at a branch; one that went
`submitted → approved` was accepted on its digital evidence. Those are materially different
and the dashboard cannot distinguish them from the current status alone.

### Notifications

**All verified channels receive every notification.** A channel in the `unverified` or
`declined` state receives nothing. Sending to only one channel is rejected as a design: a
decision is consequential, and a customer may have lost access to a channel since verifying
it.

- **On submission** — received, with the reference number.
- **On approval** — accepted.
- **On rejection** — the decision, **the reason**, an invitation to visit a branch to
  resolve it, and the reference number.

Notification dispatch is fire-and-forget relative to the profile: a failed SMS does not
change a status. Delivery results are recorded per channel in the audit trail.

### Rejection reasons — coded, not free text

Two fields, always: an **internal reason code** from a fixed list, and a **customer-facing
message** derived from it. The operator may add internal detail the customer never sees.

Free text alone is rejected: it cannot be aggregated, varies between operators, and would be
sent verbatim to a customer over SMS.

**The code list is REJ-01 to REJ-07, settled — see `operator.md`.** Fraud-related codes share
one neutral customer-facing message, so that naming a control does not tell a fraudster what
to change.

### After submission

- **Local state cleared** — a privacy requirement on a shared device, not merely storage
  hygiene. See the Stage 1 notes.
- **The app returns to a fresh start.** The next customer on the same device begins at
  stage 1a with nothing carried over.
- **There is no re-entry, including after a rejection.** A rejected customer goes to a
  branch; they do not redo the journey in the app.
- ~~**Reopening the app and entering the same account at stage 1a returns the current
  status**, so a customer can check whether their submission was approved or rejected. This
  falls out of the existing stage 1a profile check and costs nothing.~~ ⚠ **WITHDRAWN
  2026-09-12 by product-owner ruling — see BL-119's closure.** It does not "cost nothing": the
  stage 1a check is `permitAll()` (`SecurityConfiguration.java:182`), so returning the status
  would publish a bank adjudication about a named account on an unauthenticated surface. The
  ruling chose the security property over the courtesy, and the wire deliberately carries no
  status. A customer reopening the app after their local state has cleared reaches
  `EndedScreen`, which says the update has ended and directs them to a branch — true for all
  four terminal statuses and claiming none of them — reached by re-entering the account number
  at stage 1a, since with local state cleared the launch check returns a fresh start.
  **The relaunch path never consults the resume read**: `launchDecision` calls the account check
  FIRST, and that overrides the continuation to `TERMINAL` for all four statuses, so
  `POST /api/v1/submission/current` is not reached and neither are the approved/rejected screens.
  Those screens ([[BL-106]], S8-16) serve the IN-SESSION window only — the customer is in stages
  10-12 and an operator approves or rejects underneath them. The discriminator can return for the
  relaunch case later, behind the customer authentication R-051 tracks. **Amended 2026-09-14: that
  authentication is never being built (AD-017), so "later" has no date and this discriminator has no
  route back. Treat the relaunch case as permanently out of scope rather than pending.**

### Provenance

Recorded permanently: **digital** means no field was keyed by an operator, **manual** means an
operator keyed at least one customer-entered field. See the operator journey.

**Corrected 2026-09-16 (AD-022).** This used to read that `digital` meant "Uqudo scan, face match
and Civil Registry lookup all present — as distinct from a profile marked complete manually by an
operator, which has none of those". That contrast class no longer exists: manual completion is
removed, so EVERY profile is a mobile submission carrying the full identity evidence, and
provenance now answers "who typed this" rather than "is there identity evidence". Nothing about
the customer's own journey changes.

---

## Stage 13 — Resume

Not a screen. The set of rules governing re-entry after any interruption, referenced
throughout the journey. Stage 0 is where they are applied.

### The two-layer persistence model

**The device** holds a working copy, written as the customer types. It is what makes the
journey survive a connection loss mid-screen.

**The backend** holds the resume point, written per completed stage when connectivity
allows and queued when it does not.

Neither is simply authoritative. Ownership is **per field class**:

| Data | Owner | On conflict |
|---|---|---|
| Customer-entered — contact details, social status, birth data, addresses, occupation, identity type, progress | **Device** | Device wins. The customer is the source of truth for their own answers, and the device has the latest. |
| System-derived — verified channel states, Uqudo results, Civil Registry data, account status, session status, profile status | **Backend** | Backend wins. The device never overwrites these; it receives them. |

**The backend alone decides whether the session is still open.** Every resume begins by
asking. The answer can be *proceed*, *already complete*, or *blocked until X*, and it is
obeyed before any local state is used.

### Idempotency

A dropped connection hides whether a write landed. **Every mutating call from the app
carries an idempotency key**, so a retry after an unacknowledged response does not create a
second submission, a second scan record, or a duplicate profile.

### Resume cases

**Same app, local state present, backend reachable** — the common case. Reconcile silently
and land on the recorded step. **No re-authentication, no review, no rescan.** Device
continuity is itself the evidence; the session was authenticated when it began. The customer
is not asked whether to resume — asking invites people to discard work they would rather
keep.

**Same app, local state present, backend unreachable** — resume from local state with a
visible offline indicator. Offline-capable stages remain available; anything needing the
backend is unavailable until connectivity returns, and this is shown up front rather than as
a failure at the moment the customer taps Next.

**No local state — reinstall, new device, cleared storage** — entry at stage 1a, full OTP
re-verification, then the **review flow** documented under stage 1. Customer-entered data is
restored but must be explicitly reviewed, with *discard and start over* offered.
System-derived identity artifacts are **not inherited**: the resume point is capped at
identity type and the customer rescans. Prior artifacts are retained in the backend marked
**superseded**.

**Returning after a temporary block** — the backend answers *blocked until X* and the
customer sees that, not the stage they were blocked on.

**Returning to a profile completed at a branch** — the backend answers *complete*. The
journey terminates and local state is cleared. This WAS the interaction between the branch
fallback and the operator's manual-completion action. **AD-022 (2026-09-16) removed that action,
so the branch fallback has no operator-side counterpart any more: a customer who leaves for a
branch has no route to completion except returning to the app. This arm still fires for profiles
completed manually before that date, and only for those.**

**Returning to a submitted, approved or rejected profile** — stage 1a returns the current
status. There is no re-entry in any of these states.

### The stale-artifact cases

**Scan or liveness succeeded, upload failed.** The device holds a valid JWS the backend has
never seen. **The app retains it and retries the upload on reconnect** rather than making
the customer repeat the capture.

**But Uqudo deletes the session images when the enrolment JWS expires — 2 hours after the
scan completed** [OBSERVED S1-02, 2026-09-03: images `200` at +25/+31/+35/+45/+90 min, `404`
at +120 min, one second after `exp`; the Info API's documented "30 minutes" is wrong]. A JWS
can still verify perfectly after that — it is signed by Uqudo and validated against JWKS, and
the access token plays no part; at T+2h the signature still verified and the `kid` was still
in the JWKS — but the scan cannot be accepted, because acceptance requires the images. **The
practical retry window for a dropped upload is therefore within 2 hours.** The app must
distinguish *retrying an upload* from *this capture can no longer be used*, and tell the
customer plainly which has happened; the backend never decides by a timer — it attempts the
chain and reports ARTIFACT_EXPIRED or IMAGES_UNAVAILABLE. See R-012 (retired) and R-021.

**Civil Registry pending.** The scan is complete and verified; only the lookup failed. On
resume the backend simply retries it — no rescan, no new token, no repetition. See R-011.

### Abandonment

Because one device may serve several accounts, the customer must be able to **abandon** an
in-progress session and return the app to a fresh start without completing it. Available
from the resume screen; clears local state for that session.

The backend retains the incomplete profile. Abandoning on the device does not delete the
server-side record, which remains resumable through stage 1a under the review flow, and is
subject to the retention rule. **90 days from last activity** — provisional, pending OQ-001

---

## Policy values — SETTLED

All `[POLICY: …]` markers in this document resolve here, except the signature file size and
format limits at Stage 11, which remain unset.

### OTP and channel verification

| Value | Setting | Reasoning |
|---|---|---|
| Code validity | **5 minutes** | Long enough for a slow SMS route; short enough that an intercepted code has little window. Standard practice is 5–10 minutes. |
| Resend delay | **30s, then 60s, then 120s** | Progressive. 30s for the first resend because three channels verify in parallel — a customer watching a dead WhatsApp row for a full minute while SMS already arrived is the moment they abandon. Backing off protects messaging spend without punishing the first, most likely, resend. |
| Wrong-code attempts | **5 per channel**, then that channel locks for the session | Generous for mistyping six digits; hopeless for guessing. |
| Resend cap | **3 per channel per session** | Covers a genuinely delayed message; stops the screen being used to pump messages at a number. |
| Lock after all phone channels lock | **15 minutes, escalating to 1 hour** on a repeat in the same session | Five attempts against a million combinations is already hopeless, so the lock exists to stop automation, not to punish mistyping. An hour on a mandated update just produces abandoned sessions. Progressive lockout is cheap for the honest customer and expensive for a script. |

### Scan and liveness

| Value | Setting | Reasoning |
|---|---|---|
| Scan attempts per document type | **5** | Exhausting these still allows switching document type with a fresh budget. Cancels count, so five is fewer real attempts than it appears — the preparation screen exists to make the first one land. One attempt is one launched SDK session; the token backing it is single-use. |
| Total scan attempts per session | **10** | Bounds the stage regardless of switching. Held at exactly twice the per-type limit, so the document switch above is always a real offer and never a token gesture. |
| Lifetime scan tokens per profile | **20** | A separate bound that **never resets**, on how many scan tokens one profile may ever mint — roughly two full budget cycles. Exempt launches come out of it too, because it counts mints rather than spent attempts. Crossing it applies the same 24-hour block, with the same screen and the same copy; past that, a branch visit. |
| Lifetime face-session tokens per profile | **20** | The Stage 10 counterpart, capped and blocked independently of the scan side. |
| Liveness attempts | **5** | Deliberately more generous than the scan: failures are environmental, and a customer who moves to a window succeeds on the next try. |
| Block after scan or liveness exhaustion | **24 hours** | Long enough that "try later" means something; short enough that a fixable problem — better light, cleaner document — does not force a branch visit. |
| Face-match failure | **Same budget as liveness, no separate block** | The customer experience must not differentiate, or it tells a fraudster which control fired. The distinction lives in the record, not the interface. |

### Data and retention

| Value | Setting | Reasoning |
|---|---|---|
| Mandatory fields | **All of them**, every stage, every occupation | The bank's paper form is built that way. |
| Attachment limits | **10 MB max; JPEG, PNG or PDF.** Downscale images on-device before upload | A modern phone camera produces files far larger than a legible certificate needs. |
| Abandonment threshold | **30 days of inactivity** → status `abandoned` | Stays visible to operators; the customer may still walk into a branch. |
| Abandoned-profile retention | **90 days from last activity**, then identity images and personal data deleted | **Provisional.** This is a data-protection question and OQ-001 is unanswered. If the regulator says otherwise, the regulator wins. |
| Audit retention | **7 years** | Banking norm, and deliberately far longer than profile retention. The audit record of a deleted profile survives the profile. |

### Rate limiting

| Value | Setting | Reasoning |
|---|---|---|
| Account check | **10 per install per hour. No global endpoint ceiling.** | Enumeration is an accepted non-concern. A global rate would either sit high enough to be meaningless or low enough to throttle legitimate customers — Sudan's mobile traffic sits largely behind carrier NAT. The per-install limit's real job is stopping a broken client from hammering the endpoint. |
