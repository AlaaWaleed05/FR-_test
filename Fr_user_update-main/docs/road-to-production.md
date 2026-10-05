# Road to production

What stands between the system as it runs today and a first release to real customers.

Written 2026-09-07, immediately after S7-12 proved the whole solution runs end to end on AWS
(customer stages 1→12 on a real device against the project's own Uqudo tenant and the real Civil
Registry, then a real operator review and approval — profile `FRU-000000001`, `approved`).

**Read this as the plan of record for release scope.** Every item names what "done" looks like, who
owns it, and what it depends on. Where an item already has a BACKLOG or RISKS entry, that entry
holds the detail and this document holds the sequence — the ID is the link, not a decoration.

## Release scope, as decided

| Decision | Date | Consequence |
|---|---|---|
| **SMS is the only messaging channel at release.** WhatsApp and email move to the next version | 2026-09-07 PO | Forces item 1.1 and item 4.4 |
| **Google Play is the distribution route**; no Sudan-specific obstacle | 2026-09-07 PO | Forces items 2.1–2.3; makes the package identifier permanent at first upload |
| **Data residency and bank ownership are out of scope** — the bank's concern | 2026-09-07 PO | OQ-002 and OQ-018 closed by decision. Exposure assigned, not removed: staging holds a real national record in Frankfurt |
| **iOS is DROPPED** — completely, not deferred | 2026-09-10 PO (AD-003 cancelled), re-confirmed 2026-09-14 | V1 and every release after it are Android only. Every iOS row is now dispositioned: BL-052, BL-088, R-003 all closed. Reviving it re-opens all four together, at full cost. See item 5.1 |
| **Hosting stays ours; the bank is given requirements, not a migration** | 2026-09-14 PO | We run it on AWS. `docs/bank-hosting-specification.md` is the deliverable to the bank; they decide if and when to take hosting in-house. **The bank domain is NOT on the V1 critical path** — see 0.1 |

---

## 0. Bank inputs — status after the product owner's answers of 2026-09-07

Most of this section is now settled. **Updated 2026-09-14:** what remains is **one ask** — a
privacy-policy URL, all that survives of 0.1 — and **one capture** (0.2's wrong-password body).
The domain half of 0.1 left the critical path when the hosting decision was taken; see 0.1.

### Still needed

**0.1 — ⚠️ MOSTLY DISSOLVED 2026-09-14. This was step 1 of the suggested order for four days
after the decision that removed it.** The 2026-09-10 hosting decision put V1 on the CloudFront
default hostname, and the 2026-09-14 ruling settled hosting ownership: we host on AWS, the bank
gets `docs/bank-hosting-specification.md` and decides in its own time. **PROJECT_PLAN.md said on
2026-09-10 that "nothing else in the plan files now treats the hostname as a blocking bank ask
for V1" — this section was the counter-example, and it went unchecked.**

What is left of the original combined ask:

- ~~a **bank-owned domain**, plus an ACM certificate for it (**BL-074**). The app compiles its API
  address in at build time, so this must land before the first store upload or the first release is
  stranded on a `*.cloudfront.net` address~~ — **NOT a V1 item.** V1 ships on `*.cloudfront.net`
  *by decision*, so "stranded" is the wrong word for it. BL-074 is V2. **Do not re-raise this as a
  bank ask for V1.** **The struck sentence's technical half stays true and is why V2 costs what it
  does:** the address is compiled in via `--dart-define`, so the V1→V2 move is an APK rebuild and
  redistribution, not a config change, and every V1 customer must install a new APK. The four
  accepted consequences are in PROJECT_PLAN.md's hosting section.
- a **published privacy policy at a public URL** — **STILL NEEDED, and now the only blocking half
  of 0.1.** Google Play requires it and the Data safety declaration (item 2.3) cannot be written
  without it. It does *not* need the bank's domain — any URL the bank controls and will keep
  serving is enough, so this is decoupled from BL-074 and can start today.
- the **app name** and the **package identifier** — **CLOSED 2026-09-08 (S8-06):** «بياناتي» and
  `com.sfbank.bayanati`, for both tiers. Permanent after the first Play upload, as warned.

**Blocks item 2.3 only** (via the privacy-policy URL). It no longer blocks 2.1 or 2.2, both of
which are done.

**0.2 — One Airtel capture still outstanding** (body *and* HTTP status): the **wrong password**.
It matters more than it sounds: an authentication failure means the gateway is down for *everyone*
and retrying cannot fix it, so it must be distinguishable from a per-customer delivery failure —
otherwise we retry quietly while every OTP in the bank fails. Detail in **BL-080**.

**The Arabic-message-body capture was TAKEN 2026-09-07 and is closed.** A real two-segment Arabic
OTP, percent-encoded into the GET URL, returned HTTP 200 / `Status: completed` / `Total Units: 2` /
`<number> -> apiMsgId: <id> (units=2)`. No length rejection and no encoding failure, so the GET
design survives and the POST question BL-080 raised is a hygiene item rather than a redesign.

**This no longer blocks item 1.1**, which was built 2026-09-07 against the captured contract. The
wrong-password response is handled defensively and flagged `[UNVERIFIED]` in the adapter rather than
guessed — an unrecognised body becomes a TRANSIENT_FAILURE logged at ERROR, with no auth-specific
branch — and stays tracked on BL-080 as a production-robustness deferral.

**0.3 — The corrected administrative-divisions dataset** (**OQ-012**, **R-014**) — **wanted, but
NOT a release gate. Reconciled 2026-09-14:** this section filed it under "Still needed" while
RISKS.md had R-014 as ✅ *Accepted for V1, corrected dataset is V2 scope*. The RISKS position is
the right one and this section now matches it — **V1 ships on the interim list**, which is
explicitly not final and must not be treated as one. Confirmed still interim at source on
2026-09-14: `V0016__seed_admin_division.sql` has none of the three missing states. Needed as
*data*, though no longer as a code dependency. See the note under 0.9 below: the update *mechanism*
already exists and works; what is missing is a corrected list to publish through it. The current one
is interim and missing three states, and customers pick their address from it.

### Closed by product-owner decision

**0.4 — SMS password rotation: CLOSED, will not be rotated.** The credential was pasted inline in a
chat session on 2026-09-07 and the product owner has accepted that exposure. Recorded as a decision
rather than deleted, so it is visible rather than forgotten.

**0.5 — Alphanumeric sender ID registration: CLOSED, will not be requested.** Product-owner
position: the bank supplied a working send route, the registration relationship is the bank's with
its provider, and it is not ours to drive. **The question is closed; the risk is accepted, not
removed** — **R-041** stays live. Its consequence is *silent partial delivery failure*: an
unregistered sender ID can be dropped by a network with no error returned, so the send looks
successful and the customer receives nothing. **If V1 delivery turns out to be patchy by network,
this is the first thing to check.**

**0.6 — Production AWS account: CHANGED, not requested.** Production will run on the **current**
account, delivered production-ready and then handed to the bank. This closes **BL-072**'s original
framing (create a bank-owned account) and replaces it with a **handover checklist** — see 3.6, which
is new work rather than an ask. **The second, separate account for the seal export is NOT covered by
this** and remains required (**S7-10**, **R-037**): a seal bucket in the same account is protected by
the credential it polices, and S3 Object Lock is bucket-creation-only. That becomes the bank's to
create after handover.

**0.7 — A national ID card to test with: DEFERRED to the general backlog**, not a pre-production
dependency, per product-owner decision. **BL-083.** ~~One consequence to decide separately: … Either
test it before release or do not offer it.~~ **DECIDED 2026-09-11 — this consequence is no longer
open and should not be re-raised.** The ruling on BL-083: **national ID STAYS OFFERED in V1, with
the rollout GATED.** So the app keeps offering a document path that has not been exercised end to
end, and the gate on the rollout is what carries that risk instead of removing the choice. The
original text follows for the record: the app *offers* national ID as a document choice today
(`IdentityDocumentTypes`, chosen before Stage 8), so at release a customer can pick a path that has
never been exercised end to end — the same shape as the WhatsApp default in 4.4.

### Answered

**0.8 — `CheckAccount` response codes: ANSWERED.** `1`, `0` and `-1` are the complete list; the bank
has no other cases, and that covers dormant, closed and frozen accounts too (**OQ-025**, answered). **Fully closed 2026-09-07:** there is no dormant/closed/frozen distinction at this boundary at all.
The bank maps its own internal account states onto these three codes and answers with the result;
`1` means *eligible for a profile update*, and whatever internal state produced it is theirs.

Two consequences. The adapter's "any other integer → unmapped" branch is defensive-only rather than
expected, which is the right way round and needs no change. And — **worth stating so nobody
"improves" it later — we must NOT build an eligibility check of our own.** A reader seeing
`1 = Account Found` could reasonably decide the system should also verify the account is active;
that would duplicate a rule the bank already owns, on data we do not have, and the two would drift.
The middleware's answer is the eligibility decision.

**0.9 — Reference lists updatable in production without an app update: ALREADY BUILT, exactly as
requested.** No work needed. The product owner asked for lists that can be improved in production,
with the app checking on open for a newer version and updating if there is one. That is precisely
the shipped design, and it is a CLAUDE.md hard rule ("server-supplied, cached, and version-checked"):

- `GET /api/v1/reference/manifest` returns every list's `version` and `contentHash` under one
  `catalogHash`
- the app calls `syncCatalog()` at Stage 1a (`branchCatalogInitProvider`) and again in the data-entry
  repository, so it *does* check on open
- the manifest fetch is a conditional `If-None-Match` GET, so an unchanged catalogue costs a `304`
  and nothing else; only lists whose `(version, contentHash)` differ are re-fetched
- received bytes are **SHA-256 verified against the manifest's `contentHash`** and discarded on
  mismatch
- the list version used for a submission is recorded on the profile, so an old submission stays
  interpretable after a list changes

Publishing a corrected list is therefore a server-side operation with no app release. **The
mechanism is done; only the corrected data (0.3) is outstanding.**

---

## 1. Blockers — the product does not work without these

### 1.1 SMS adapter — **BL-080**

Without it a real customer never receives an OTP and cannot pass stage 2. Everything else on this
page is secondary to it.

The contract is captured: `GET https://www.airtel.sd/api/html_send_sms/`, plain-text three-line
response, `Status: completed` / `Total Units: N` / `<number> -> apiMsgId: <id> (units=N)` on
success, `Status: failed` / `Total Units: 0` / `<number> -> FAILED: <reason>` on failure. `apiMsgId`
and the unit count map onto columns that already exist, so **no schema change**.

**Done means:** a real code arrives on a real handset; a failure is recorded as a failure; and
`ACCEPTED_PROVIDERS` accepts `http` for SMS.

**Status 2026-09-07 (S8-01): BUILT, two of the three met.** `ACCEPTED_PROVIDERS` is now
`List.of(STUB, HTTP)` and `http` is accepted **for SMS only** — it is refused at startup for
WhatsApp and email, which have no real adapter (SMS-only release). A failure is recorded as a
failure: success is positively confirmed (`Status: completed` AND an `apiMsgId` AND exactly one
recipient line AND the echoed number equal to what was sent), and every unrecognised body is a
failure, never a silent delivery. **The third — a real code on a real handset — was NOT met by that
session**: no live send was made, and `AirtelSmsSenderLiveTest` is env-gated and inert.

**MET 2026-09-07 at S8-02 — item 1.1 is CLOSED.** A real Arabic OTP was sent from the deployed AWS
stack through the real gateway and arrived on a real handset in the same minute: `ACCEPTED`,
`Status: completed`, `apiMsgId 4526963`, `Total Units: 2`, 1969 ms to the gateway. Arabic rendered
correctly and the sender displayed as `SFB`. All three conditions of "done" are now met.
**Read R-041 before generalising it:** the handset was on **Sudani**, not the gateway operator's own
network, so this proves interconnect delivery works on one network — it does not clear Zain or MTN,
and R-041 is a claim about partial failure BY network. Three phones in V1 still closes it.

Three things that will go wrong if not designed in from the start, all detailed in BL-080:

- **HTTP status is 200 on success *and* failure.** The obvious implementation reports every failed
  OTP as delivered. Success must be *positively confirmed*, and any unrecognised body treated as a
  failure — testing for the word `FAILED` is not enough, because an error page contains no `FAILED`.
- **We store `+249…`; Airtel wants `0…`.** A missing conversion produces `Invalid Sudanese number` —
  which the system would record as *the customer's* number being bad. Support chases a correct
  number while the real cause points away from us. This is S7-12's Civil Registry defect in a new
  place. Convert at the boundary and verify the echoed number against what was *sent*.
- ~~Per CLAUDE.md, `@agent-researcher` runs against the real service before any integration code.~~
  **WAIVED as AD-009, 2026-09-07:** Airtel Sudan publishes no public API documentation, so the
  captured contract is the basis of record.

**Depended on** 0.1, 0.2 — no longer blocked: 0.2's Arabic capture was taken 2026-09-07 and the
wrong-password half is deferred rather than blocking (see 0.2). **Related risk:** R-043 (SMS spend)
is largely resolved — a domestic route removes the ~$427k Twilio exposure.

### 1.2 Operator image viewing — **BL-075**, CLOSED

At S7-12 a real operator approved a real profile **without seeing the passport, the registry
photograph or the liveness capture.** At that point the back office showed placeholders and a
metadata table, and there was no operator-facing image endpoint at all. The images were stored
correctly — it was purely a missing viewing mechanism. **Both halves of that mechanism now exist
(see Status below) and the placeholders are gone.**

**R-046 is CLOSED (2026-09-13) and was never the trade-off it looked like.** It framed a choice
between a signed URL (which fires the "image viewed" audit event at issue time, redeemable twice or
by another session) and a blob fetch. Both horns rested on "an `<img>` cannot carry an
`Authorization` header" — true, and irrelevant here: the back office uses a session cookie and S7-08
made it same-origin with the API, so the answer is an ordinary cookie-authenticated `GET`. It keeps
the property that made the choice load-bearing: every view is an origin request, so operator.md's
"viewing an image is its own audit event" fires at VIEW time.

**Status: DONE.** The backend endpoint shipped at S8-22 — per-profile and per-artifact
authorization, `Cache-Control: no-store, private`, an allow-list for both kind and served content
type, reads through `app.artifact_read()`, one audit event per fetch. The back office calls it at
S8-23: `ArtifactContactSheet` links the viewable kinds into tiles, each clickable into a preview,
and S8-24 added the salary certificate as a sixth (BL-136), with a PDF one opening in a modal. **One caveat for any acceptance walk:** `portrait_registry` is a 32-byte ASCII string
under the civil-registry stub, so a stubbed run proves the LAYOUT only — the Uqudo-vs-registry face
comparison needs a real Civil Registry response to be judged at all.

**Done means:** an operator can see the six viewable artifacts, each view is audited, and the
review is a real check rather than a rubber stamp. One stored kind is still excluded, and by
decision rather than omission: `doc_back`, per wayfinder ticket 02. `salary_certificate` WAS the
omission this paragraph used to name — **BL-136, closed at S8-24 by product-owner decision**, so
income evidence is viewable and the only remaining gap is a deliberate one.

The three build requirements were met as follows. `doc_front_frame`/`doc_back_frame` are **not**
offered — AD-004 stores no bytes for them, and the attachments table that used to list the front
frame as a 1.7 MB item is gone along with the rest of the table; the client never links them and the
endpoint 404s them anyway. Attachments are **clickable into a pop-up** (PO request), which is what
the tiles do — though note the table's removal means the "those not already displayed" half of that
request is retired rather than met. The audit event fires on view.

### 1.3 Identity images are fetchable by anyone with a profile UUID — **R-051** — ❌ **DROPPED 2026-09-14 (AD-017)**

**Product-owner decision: customer-session authentication will not be built, in V1 or after.** This
is no longer a blocker, an entry gate, or work anyone is scheduled to do. It is an accepted exposure.

**This section was also stale before that decision, and the staleness is worth recording** because
it misled a planning answer on 2026-09-13: R-051 had already been ruled *deferred to V2* on
2026-09-10 (PROJECT_PLAN.md), while this page still presented it as an open blocker and RISKS.md
still carried it 🔴 Live. Three documents, three different states, for four days.

What is accepted, stated here so this page does not read as though the problem went away: the
customer-facing image endpoint has no customer session behind it, so a profile's passport and
national-ID scans, Uqudo portrait and Civil Registry photograph are served to anyone holding that
profile's UUID. Partial defences remain in place and are not the fix — uniform 404s that never
confirm a guess, `Cache-Control: no-store`, an allowlist of kinds. A served image cannot be
unserved.

~~**Done means:** an image request without a valid customer session for that profile is refused.~~
~~Real passports of real people behind a guessable identifier is the item I would not carry into
production under any schedule pressure.~~ That was this document's own recommendation. It was put to
the product owner on 2026-09-14 with that framing and overruled, which is the outcome recorded here
rather than the recommendation quietly deleted.

---

## 2. Release engineering — required to publish at all

### 2.1 Rename to SFB — **BL-081** — ✅ **DONE 2026-09-08 (S8-06)**

The backend's package root was `sd.gov.bank.fruserupdate`. `sd.gov.bank` denotes a **government**
bank; the Sudanese French Bank is commercial. The name was not merely unbranded, it was wrong, across
**456 source files** plus the build's `groupId`, its JaCoCo exclude paths, and CLAUDE.md's own
package-scoped coverage rule.

**Now `com.sfbank.bayanati`** — product-owner decision, 2026-09-08: `com` commercial, `sfbank` the
bank's public domain (`sfbank-sd.com`), `bayanati` the app name «بياناتي». This closes the third of
BL-081's three bank-owned decisions; the row had only ever speculated `sd.sfbank.*`.

Done as one dedicated session, as this section asked. Backend and mobile share the one identifier,
so 2.2 was closed in the same pass rather than after it. Historical session reports under
`docs/sessions/` deliberately keep the old root — they paste verbatim gate output from the days they
describe, and rewriting them would make a dated report claim a tree that never existed.

**Depended on** 0.5.

⚠ **CORRECTED 2026-09-07.** This previously also read "**Must precede** … 3.4 — branded assets added under
the old names mean renaming twice." **It does not bind 3.4's mobile work.** Android resources live under
`res/`, not under the package path, and survive an `applicationId` change untouched. Proven rather than
argued: the mobile branding landed on 2026-09-07 (icons, `@string/app_name`, colours, splash, font) with
this rename still outstanding, and nothing it added sits under a package path. See §3.4.

### 2.2 Application identifier — **BL-079** — ✅ **DONE 2026-09-08 (S8-06)**

Was Flutter's template default, `com.example.mobile`. **Play rejects `com.example.*` outright** —
a hard rejection, not a style note. Permanent after first upload, which is why it had to be settled
before any submission rather than at submission time.

Now `com.sfbank.bayanati`, identical to the backend package root: `applicationId` **and** `namespace`
in `mobile/android/app/build.gradle.kts`, the `MainActivity.kt` package and its directory. The iOS
`PRODUCT_BUNDLE_IDENTIFIER` was changed in the same pass (`Runner` and `RunnerTests`) rather than
left to BL-052, since it carries the identical lock-on-first-upload property — but iOS is not
compiled on this machine, so that half is **[UNVERIFIED] by build**. Android was proven by install
and launch on the emulator, not merely by compiling.

### 2.3 Play Store technical readiness — **BL-082**

Four blockers, one now cleared — none of them the store's fault:

1. ~~The application id above.~~ **Cleared 2026-09-08 (S8-06)** — now `com.sfbank.bayanati`.
2. **The release build is signed with the DEBUG key** — `mobile/android/app/build.gradle.kts:63-65`
   still carries the template's `signingConfig = signingConfigs.getByName("debug")` and its `TODO`.
   (**Line reference corrected 2026-09-14: this cited `:45-48`, which is now the S8-09 AAB
   language-split comment.**)
   Needs a real upload keystore and Play App Signing enrolment. **Whoever holds that key must not
   lose it**: without Play App Signing, losing it means the app can never be updated again. No
   keystore or `key.properties` exists today and neither may ever be committed.
3. **Play requires an app bundle (`.aab`)**; every build this project has produced is an APK.
   `flutter build appbundle` has never been run, so it is unproven — and an AAB splits per-ABI,
   which is exactly where an Android-only native dependency first shows itself.
4. ~~**The app has no identity:** the manifest's `android:label` is `"mobile"` and the launcher icon
   is still Flutter's default, with no adaptive icon.~~ **CLEARED 2026-09-07 by §3.4's branding
   work; this blocker sat here contradicting §3.4 of this same document for a week.** Verified at
   source 2026-09-14: `android:label="@string/app_name"` = «بياناتي», adaptive
   (`mipmap-anydpi-v26`) and legacy launcher icons present at all five densities.

**So two of the four remain: the debug signing key (2) and the unbuilt AAB (3).** Both verified
still true at source 2026-09-14 — `build.gradle.kts:63-65` still carries the template
`signingConfig = signingConfigs.getByName("debug")` and its `TODO`.

Plus the Console requirements: a privacy-policy URL and the **Data safety** declaration. That
declaration is heavier here than for most apps — government identity documents, a face biometric and
a national number — and needs **0.1**'s privacy-policy URL first. (**Corrected 2026-09-14: this
read "needs 0.4 first". 0.4 is the closed SMS password rotation and has nothing to do with it.**)

`compileSdk 37` / `targetSdk 36` / `minSdk 24` are current and are not a concern.

---

## 3. Operations and data protection

### 3.1 Nothing schedules the two housekeeping jobs — **R-037**, mapping M-22

`audit.seal_create()` and `app.purge_abandoned_artifacts()` both exist as database functions and
**neither is scheduled by anything.** The app has scheduling enabled and drains the notification
outbox on a timer, but no seal job and no purge job.

Consequences, both silent and both accumulating: the audit seal chain never advances, so the
tamper-evidence the design promises is never actually produced; and abandoned customers' passport
images are never deleted, which is a retention breach that grows daily.

**Done means:** both run on a schedule, failures are visible, and overlapping runs are safe
(V0032 already documents the overlap hazard).

### 3.2 Seal export to a separate account — **S7-10**, **R-037**

Blocked on 0.6. S3 Object Lock in Compliance mode, in a **bank-owned account that is not the one
running the application** — a seal bucket in the same account is protected by the credential it
polices. Object Lock is at-bucket-creation only.

### 3.3 Admin account management — **BL-023**

There is no HTTP surface for listing operator accounts, disabling one, or changing a role, and no
password reset. Today a departing employee is disabled by a DBA running SQL, and a forgotten
password means deleting the row and re-running a CLI tool.

**The design already anticipates this.** An `admin` role exists, and the security configuration
already reserves `/api/v1/admin/**` locked to `ADMIN` with nothing behind it. So this is building
endpoints and screens into a shape that is already there, not designing something new.

Updated 2026-09-13 (AD-013, built at S8-28): admin is no longer "deliberately excluded from
operator screens" — it is the top of the role ladder and holds every operator power. What remains
unbuilt is only the admin's OWN surface, the user-management endpoints and screens below.

**Done means:** an admin can list, create, disable and re-role operators, and reset a password,
through the back office — with each action audited as an operator action.

### 3.4 UI polish, bank logo, look and feel — **BL-076**

Carries everything observed on real hardware at S7-12: the bank logo; the non-idiomatic Arabic in
the back-office status history (it renders the previous state as a bare `(من <status>)`, reading as
a literal translation of a database column); ISO-8601 dates in an Arabic-first UI in **both** tiers
where the Civil Registry's own convention is `DD/MM/YYYY`; a literal `--` where an em-dash belongs;
the one Latin-script field sitting differently from its Arabic neighbours; a portrait-shaped
signature preview for a landscape signature; and a mostly-empty pre-submit screen.

⚠ **CORRECTED 2026-09-07.** This section previously read "**Depends on** 2.1 — do not add branded assets under the old package names." **That dependency binds the BACKEND tier only.** For the **mobile** tier it does not hold: Android resources live under `res/`, not under the package path, and survive an `applicationId` change untouched — only `MainActivity.kt` sits under the package path. **Confirmed by the product owner 2026-09-07: the mobile branding and UI polish work is NOT gated on the backend Java package rename (2.1) and may proceed immediately.**

✅ **LARGELY DONE 2026-09-07** — pre-release UI build (commit `7f87544` and the wording commit that follows it).

**Landed:** brand blue `#105097` pinned as the theme `primary` (seeding alone returns a lighter tonal value,
so it is pinned explicitly); emblem-only adaptive + legacy launcher icons at all five densities, generated
from the one master by `mobile/tool/generate_design3_assets.py` (which replaced
`generate_brand_assets.py` at AD-012's launcher ruling, 2026-09-12); `android:label` moved off the literal
`"mobile"` to `@string/app_name` = «بياناتي»; a brand splash on all three launch paths that are actually
live (`drawable-v21`, `values-v31`, and `values-night` made identical to `values`); IBM Plex Sans Arabic
bundled, Unicode-RANGE-subset so a customer's scanned name cannot render as boxes; `letterSpacing: 0` and
`height: 1.6`; the RTL page-transition defect fixed with a direction-neutral builder; `textInputAction`
across 23 fields; an `LtrValue` isolation widget; the back-office status-history sentence, the em-dash, and
`DD/MM/YYYY` in both tiers **at two back-office sites, not the one this section named**.

**NOT done, and deliberately — each is now its own row:** Stage 12's "check your answers" data summary
(**BL-087** — there is no honest source for it; the local draft can hold values the backend never received),
and the WhatsApp row disable (**BL-086** — it is a backend/flag change, not presentation; its
default-selection half did land at S8-05, see §4.4). ~~and iOS's `Info.plist` display name, which
still reads `Mobile` (**BL-088**)~~ — **struck 2026-09-14: BL-088 is closed, iOS does not ship.**
BL-071's Uqudo SDK strings stay separate.

**Design decisions are settled.** This section's work is now fully specified — brand colour, typeface, text metrics, transitions, focus rules, splash, and the approved Arabic copy verbatim — in `docs/sessions/2026-09-07-research-ui-ux-design-plan.md` §0 (D1–D10) with a 16-item build order in §5. The build session implements those decisions and does **not** re-derive wording, colours or typography. Two findings from that plan worth surfacing here: the app currently ships a page transition that **slides the wrong way in RTL** (no built-in Flutter transition mirrors for RTL), and it has **no theme and no bundled font**, so it renders differently on the test device than on any review machine.

### 3.5 Database upgrade runbook — **BL-073**

Every future RDS major-version upgrade **silently removes** the audit Layer 3 event triggers. Nothing
fails and nothing warns. The runbook the bank inherits needs an explicit reinstall step plus the
`pg_event_trigger` verification query as a post-upgrade gate.

---

### 3.6 AWS account handover to the bank — **BL-072**, reframed

Product-owner decision 2026-09-07: production runs on the **current** account, delivered
production-ready, and the bank takes it from there. So this is no longer "ask the bank to create an
account" — it is a handover procedure, and it is real work that must be done deliberately rather
than by sending someone a password.

**Not to be confused with the hosting specification (ruling of 2026-09-14).** There are two
distinct things and they have been conflated once already:

- **This section (3.6)** transfers *the AWS account we run* — root email, MFA, billing, IAM keys,
  KMS policies, restore drill. It is owed regardless, because the account is currently rooted on a
  personal mailbox.
- **`docs/bank-hosting-specification.md`** describes *infrastructure the bank would run itself*,
  if it ever chooses to. It is a specification for agreement, handed over and then left with them.
  **We do not chase it, schedule it, or block anything on it.** The bank decides if and when.

Neither obliges us to migrate anything. We host on AWS; the bank's choice is theirs and has no
V1 deadline attached to it.

The account is currently rooted on a personal Gmail. Until every step below is done, **whoever
controls that mailbox controls the bank's KMS keys and every backup of every passport image** — a
password reset to that address is enough. Handover is what closes that, and it is not closed by
deploying to production.

**Done means, in order:**

1. Root email changed to a **bank-controlled mailbox** (ideally a distribution list, not a person)
2. Root password reset by the bank, and **root MFA re-enrolled on a bank-held device** — the current
   MFA must be removed as part of the same sitting, not afterwards
3. Billing contact and payment method changed to the bank's
4. The `fru-deploy` IAM user's access keys **rotated or deleted**; any other IAM principal reviewed
   and removed if it belongs to the delivery team
5. Account moved into the bank's AWS Organization if they have one
6. KMS key policies re-checked *after* the above — a key policy that names a deleted principal locks
   the data it protects, and this is the step where an encrypted database becomes unrecoverable if
   it is done carelessly
7. A restore drill **after** handover, proving the bank can actually recover the database with the
   keys they now hold

Step 6 is the one that can destroy data. Sequence it as written and verify before removing anything.

---

### 3.7 Staging schema version — current, and how it is kept that way

**Staging is at `V0074` as of 2026-09-16 — read at S9-04 and RE-READ at S9-05.** `flyway_schema_history` reads 74 versioned rows, no failures, strictly ascending, and the repo's highest migration is also `V0074`, so S9-05 deployed code alone and migrated nothing. S9-05 ran `flyway:validate` first and it reported `Successfully validated 74 migrations` with no pending migration and no checksum mismatch — BL-144's trap did not fire, which is a second clean reading rather than the gate BL-144 asks for.

Two facts about this that keep being re-learned, so they are written here rather than left in a
session report:

- **A deployment does not migrate.** `spring-boot-flyway` is `test`-scope by deliberate design, so
  the shipped jar connects only as `fru_app`, which holds no DDL rights. It starts happily against
  a schema several versions behind and then fails *per endpoint* with `permission denied` or
  `bad SQL grammar` — never at startup. Migration is a separate, explicit act, run as `fru_migrator`
  over an SSM port-forward before the image is rolled out. Found live at S5-01, re-confirmed S8-32.
- **The recorded version is only as good as its last reading.** Between S8-32 and S9-04 two session
  reports carried "staging is at V0072". Neither had read the database; the figure was inherited
  from a third report and passed along. It was wrong — staging was at **V0070**, four migrations
  behind rather than two. Do not quote this section's number as evidence; re-read
  `flyway_schema_history` and update the line above.

Before migrating, always `flyway:validate` first and read what it says. A checksum mismatch means an
already-applied file was edited after the database ran it, and `flyway repair` is both the documented
fix *and* the way to silently record agreement between a file and a database that no longer match.
Find the commit, read the diff, and repair only if the change provably touched no DDL. See **BL-144**.

## 4. Defects and gaps worth closing before real customers

**4.1 `resume_stage` is a dead column — BL-077.** Always reads 1; a profile that reached `approved`
still reads 1. Nothing writes it, nothing reads it, and its own comment claims it is the "last
completed journey stage". Support would be misled. Decide: drop it, or populate it.

**4.2 `SameSite` is unstated on both operator cookies — BL-078.** They inherit a browser default.
Low exposure today (same-origin), but it is the *same mistake* BL-066 was — an attribute derived
rather than stated — on the same two cookies.

**4.3 No rate limiting on the account-check endpoint — BL-007.** The one unauthenticated,
enumerable entry point in the system.

**4.4 WhatsApp — the default-selection half is DONE; the flag half is not.** ~~Stage 1b has SMS and
WhatsApp **both selected by default**. … **Deselect it by default, or hide it.**~~ **Done
2026-09-08 at S8-05 (commit `2b0cd9f`), and this section was never updated.** Verified at source
2026-09-14: `mobile/lib/core/entry/entry_repository.dart:32` carries the reason in a comment and
`whatsappSelected = false` on line 44; `contact_channels_screen.dart:47` matches; `customer.md:105`
was amended the same day. So a customer no longer gets a WhatsApp code they cannot receive unless
they deliberately tick the box.

**What remains is BL-086**, which is a different fix and the one R-042 actually called cleaner: on
a deployment that does **not** set `fru.messaging.whatsapp.enabled=false`, a customer who *does*
tick WhatsApp still gets a challenge nothing can deliver, because WhatsApp is then only deselected
in the UI and not disabled at the flag/challenge level. **Scoped 2026-09-14 (S8-32):** staging has
set that flag since S8-08 and now sets it from `08-backend-service.sh` itself (BL-097 closed), so
on staging the channel is recorded `declined` and no challenge is minted. The per-deployment
"channels this deployment can actually verify" field in the stage 1b contract and the manifest is
still unbuilt. R-042 stays live on that narrower basis, not on the default-selection basis.

**4.5 ~~Manual completion bypasses every identity check — R-018.~~ DISCHARGED 2026-09-16 — AD-022 REMOVES manual completion (built at S9-01), so this path does not exist in production and there is nothing left to gate. R-018 is retired with it. Original text follows.** An operator-completed profile skips
Uqudo, face match and the Civil Registry entirely. ~~Confirm the four-eyes rule and the audit trail are
sufficient control~~ — **the four-eyes half is GONE as of 2026-09-13 (AD-013), so this gate is now
harder, not discharged: the audit trail is the ONLY control left to confirm sufficient** before this
path is available in production. See R-054, and note its own prerequisite R-037 (audit seals are
produced but never exported, so the trail has no tamper-evidence anchor outside the database).

**4.6 Arabic strings for the Uqudo SDK screens — BL-071.** About 176 phrases. The scan and liveness
screens are English today, against an Arabic-first requirement.

**4.7 ~~The four-eyes rule has never been tested.~~ MOOT as of 2026-09-13 — AD-013 REMOVES the rule, so there is nothing left to test. This gate is discharged by deletion, not by a passing test. What replaces it as a production concern is R-054: no separation of duties remains and the audit trail is the sole compensating control.** Original text: It gates approval of a *manually completed*
profile; S7-12's profile was digitally submitted, so the rule never engaged. Needs a manually
completed profile and a second operator.

**4.8 `@agent-reviewer` is owed on the S7-12 diff** — required before a task is marked done, and not
run because that session was instructed not to spawn subagents. The Civil Registry canonicalisation
is the part that wants a second pair of eyes: its first draft was wrong and only a pre-existing test
caught it.

---

## 5. Open decisions — ours or the product owner's, not the bank's

**5.1 iOS — BL-052, R-003 — ✅ CLOSED.** This asked for an explicit decision instead of an omission.
It got one twice over: **AD-003 was cancelled 2026-09-10 — iOS is dropped, V1 is Android only** — and
that was confirmed again on 2026-09-14, when the consequences were finally applied. This section
stood stale for four days *after* the decision it asked for had been taken, which is the failure mode
it was itself complaining about.

Applied 2026-09-14: the CLAUDE.md rule requiring a per-package iOS support check is **deleted** (it
had been costing effort on every Flutter package added, for a platform nobody ships); **BL-052** is
closed not-applicable; **R-003** is marked ❌ no longer applicable. The counter-argument stays on the
record for anyone revisiting it — bank executives disproportionately carry iPhones, and the work is
zero-percent done rather than half-done, so reviving iOS costs the full amount and re-opens AD-003,
BL-052 and R-003 together.

**Re-confirmed and completed 2026-09-14 (second ruling the same day): drop iOS COMPLETELY.** The
remaining live iOS statements were removed rather than left to rot — the release-scope table at the
top of this page (which still read "not yet decided"), `CLAUDE.md`'s module line, PROJECT_PLAN's
platform line, module map, OQ-009 and its AD-003 prerequisite entry, and
`docs/bank-hosting-specification.md`. **BL-088 is now closed** as well: the `Info.plist` display
name it tracked belongs to a platform that does not ship. The research cards under
`docs/components/` keep their iOS evidence columns untouched — they are dated records of what was
observed, and rewriting them would make a report claim a check it never made.

**5.2 R-046's signed-URL versus blob decision** — **CLOSED 2026-09-13**, by refuting the premise
rather than picking a horn. See 1.2. No longer blocks anything; what remains under 1.2 is ordinary
front-end work.

**5.3 R-026 is probably stale — ✅ CONFIRMED AND RECONCILED 2026-09-14.** It asked for disk/volume-level
encryption; S7-03 delivered exactly that — a customer-managed KMS key, `StorageEncrypted: true`,
backups inheriting it — and AD-002d settled the hosting condition the row set for itself. **R-026 is
now ✅ Retired.** No work was owed; the row was behind the code, not ahead of it.

---

## Suggested order

**Rewritten 2026-09-14.** The previous order's first three steps were all completed or dissolved
work — 1.1 closed 2026-09-07, 2.1 done 2026-09-08, and the domain ask taken off the critical path
by the hosting decision — while only step 4 had been maintained. What actually remains:

1. **3.1 — schedule the two housekeeping jobs (R-037).** The only item on this page that would
   stop a launch **on data-protection grounds** — 2.3's two blockers below stop a Play
   *submission*, which is a different kind of stop: they are prerequisites of the distribution
   route, not defects in the running system. 3.1 is also the only one accruing damage every day
   it waits: the audit seal chain never
   advances and abandoned customers' passport images are never deleted. Verified still true at
   source 2026-09-14 — the only `@Scheduled` in the backend is the outbox dispatcher, and every
   mention of `audit.seal_create()` and `app.purge_abandoned_artifacts()` in Java is a comment.
2. **Then 2.3's two real blockers** — the upload keystore and Play App Signing enrolment, and a
   first `flutter build appbundle`. Both are ours and neither has a lead time we do not control.
3. **In parallel, ask the bank for the privacy-policy URL** (0.1's surviving half). It is the only
   external lead-time item left and it gates the Data safety declaration, not the release.
4. **Then the rest of section 3**, with 3.6 (handover) sequenced carefully — step 6 of it destroys
   data if done out of order — and 3.2 (seal export) after 3.1, since it is the same risk's other
   half.
5. **Then section 4.** Nothing in it blocks a release.

**Not on this list, deliberately:** the bank domain (V2, BL-074), core banking (stubbed by
decision — the bank calls the cutover, BL-089), iOS (dropped), and R-051 (dropped, AD-017).

**What I would refuse to launch without:** ~~section 1 in full, and 3.1~~ — **3.1, and 3.1 alone,
as of 2026-09-14.** Section 1's remaining item (1.3 / R-051) was dropped by product-owner decision
rather than met, so this line no longer covers it; the exposure is accepted and recorded at AD-017,
not resolved. **3.1 is now the only thing on this page I would still stop a launch for**, and
accepting R-054 (AD-018) makes it weigh more, not less: with separation of duties gone, the audit
trail is the sole remaining control over an operator who keys in and approves the same profile — and
until the seals leave the database, that trail's custodian can rewrite it. Everything else is a
judgement about how much rough edge a first production release can carry.

**Five accepted risks to keep visible** — all closed as questions, all live as exposures. Closed is
not solved, and this list exists so nobody reads a tidy risk register as a safe one:

- **R-041** — the SMS sender ID is unregistered. Silent partial delivery failure by network is the
  symptom to watch for in V1; only Sudani is proven.
- **The AWS account remains personally rooted** until 3.6 completes.
- **R-051 / AD-017 (2026-09-14)** — identity-document images are served to anyone holding a profile
  UUID, and no customer-session authentication will ever be built. Irreversible on disclosure.
  BL-137 and BL-041's residual are accepted with it.
- **R-054 / AD-018 (2026-09-14)** — one back-office account can key in a profile and approve it.
  Nothing prevents it and nothing detects it; no one is assigned to read the audit trail.
- **AD-019 (2026-09-14)** — a device-less re-entry inherits the previous person's salary certificate
  and signature, and an operator is shown them as the new customer's, unmarked.
