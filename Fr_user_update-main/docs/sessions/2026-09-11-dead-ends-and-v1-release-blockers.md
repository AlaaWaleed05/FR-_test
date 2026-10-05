# Dead ends and V1 release blockers — 2026-09-11

**The phase gate fired.** One of the four dead ends permanently burns a single-use account, so
Phase 2 (BL-105, BL-106, BL-021) was **not started**, exactly as the session's own rule requires.
All Phase 1 verification and all plan-file work is complete.

Provenance: [OBSERVED] = checked at the named file or command · [DOC] = a document says so ·
[UNVERIFIED] = inferred, and labelled as such.

---

# Done

## The four dead ends — real or not, and what each costs the customer

Every verdict was checked against live source, then independently re-checked by
`@agent-reviewer`, which corrected two of my four ratings. **The audit document's description of
source was not treated as source.**

| # | Dead end | Real? | What it costs the customer |
|---|---|---|---|
| 1 | Stage 8/10 lifetime-mint cap | **REAL** | **Permanently burns a single-use account** |
| 2 | Stage 11 signature | **HARM CLASS REFUTED** | Merely annoying — nothing is spent |
| 3 | Stage 0 unmapped `BLOCKED` | **REAL** (and it *is* BL-021) | Temporary hard stop with a false explanation |
| 4 | Stage 3/4 reference-list error | **REAL** | Merely annoying |

### 1. The lifetime-cap loop — REAL, and this is the blocker

**What happens.** Each profile may mint at most 20 scan tokens and 20 face tokens in its
lifetime. Once a customer crosses that, the backend blocks them for 24 hours. When those 24 hours
pass, the cap still holds — so every later attempt is refused with **the same, now-expired
deadline**. The app reads an expired deadline as "no wait remaining" and tells the customer
**«يمكنك المحاولة مرة أخرى الآن»** — *"you can try again now"* — and shows a retry button. That
is false, and it is false forever. The one control on the screen cannot work. [OBSERVED]

**Why it is permanent.** `app.profile.scan_tokens_minted` and `face_tokens_minted` have exactly
**one writer each in the entire backend**, `= x + 1` (`JdbcIdentityScanRepository.java:75`,
`JdbcLivenessRepository.java:76`). **No reset exists** — not in any migration, scheduled job,
service, or back-office action. `JdbcDeviceLessReentrySuperseder.java:82-87` records the omission
as deliberate. The cap check sits *above* the block-lift in `IdentityScanService.java:205-242`, so
a capped profile's block is never lifted. [OBSERVED]

**Reachable by an ordinary customer — no race, no crash, no hostile client.**
`identity_scan_repository.dart:96` mints the token **before** the camera opens at `:107`
(liveness mirrors this at `liveness_repository.dart:64`/`:78`). A camera-permission denial is
deliberately exempt from the *attempt* budget — but still costs a **lifetime mint**. Twenty
permission denials cap a profile forever without a single scan being attempted. [OBSERVED]

**Neither stage has a real way out.** Stage 10 passes no exit control at all
(`stage10_screen.dart:369-374` — that is BL-109). Stage 8 does pass one, but it routes to the
launch screen, which re-resolves straight back to Stage 8 — a loop with one extra hop, not an
exit. *(This correction is `@agent-reviewer`'s; I had rated Stage 8 as having an exit.)*
[OBSERVED]

**Closing it: two tiers.** Filed as **BL-114** with its three causes separated, because they are
not the same kind of thing — two are defects, one is a decision that was never taken. See
*Needs your attention*.

### 2. Stage 11 — the reported harm class is REFUTED

The audit carried this as **WASTES A SINGLE-USE ACCOUNT**. That rating is **wrong and is
withdrawn**. Nothing at Stage 11 is spent or expires: no counter, no token mint, no deadline, and
`SignatureEligibility` reads a **persisted** `facePassed`, so leaving and relaunching forces no
liveness redo and burns no budget. A rejected signature is retryable, with clear-and-redo on the
screen. [OBSERVED]

What *is* real is much smaller and is filed as **BL-115**: the submit button is disabled until a
capture exists, there is no Back, and **the screen never mentions a branch**. A customer who can
neither draw nor photograph a signature sits there with no usable control and no advice — but
loses only an unsubmitted drawing and resumes at Stage 11 on relaunch. Mobile-only fix.

**This is the second BL-061-style refutation.** It is recorded in BL-115 explicitly so it is not
re-found and re-filed a third time.

### 3. Stage 0 — REAL, and it is BL-021, not a separate defect

`AccountContinuation` has only `proceed`/`terminal`/`retry` (`entry_models.dart:7`), so
`byName("blocked")` throws (`dio_entry_api.dart:32-34`). The backend has sent `BLOCKED` since
S4-06. The error escapes to the launch screen, which renders **one fixed sentence blaming the
customer's internet connection**, with no retry control. [OBSERVED]

**Two corrections to how this was framed, both of which make it less severe than reported:**

- **It is not permanent and not "cannot open the app at all".** `BLOCKED` is emitted only while
  the lock is still live, so it **self-heals**: 15 minutes on first occurrence, 1 hour after.
- **"LOSES DATA" was wrong.** Nothing on this path clears the session, so the local draft
  survives. Data is lost **only if the customer clears app data** — which the false "check your
  internet" advice makes more likely, but does not cause.

**And one correction that makes it wider:** the same unmapped value also reaches **Stage 1a**,
where it surfaces as an **account-number field error** for a phone-lock condition
(`account_entry_screen.dart:74`, `:96-102`). Retryable, so not a dead end — but the customer is
told the wrong field is wrong. BL-021 has been amended with all three corrections.

**The comment that caused this** is the clearest example of the new CLAUDE.md rule:
`entry_repository.dart:428-444` is a long, confident comment asserting that account-check "still
has no wire signal" for blocked and that a relaunch "self-corrects". Both were true before S4-06
and are false now. The comment froze an old truth and stopped the next session checking.

### 4. Stage 3/4 reference-list error — REAL, merely annoying

A failed reference-list load replaces a **mandatory** dropdown with a bare sentence and no retry,
on a screen with no Back. The customer cannot complete the field and cannot go back. But the
draft survives (persistence is per-field, not save-on-submit), nothing is spent, and relaunching
retries. Filed as **BL-116**, widened to Stage 4, which has the same shape. **The fix already
exists in this repo** — `account_entry_screen.dart:133-134` renders a retry widget that
invalidates the provider; it was simply never propagated. Mobile-only.

## The gate decision

**BLOCKER-CLASS, on finding 1**, on both limbs of the rule: it permanently burns a single-use
account, *and* it changes what BL-021's fix should do — both are "the customer is shown a false,
unresolvable screen at a block", and they want **one honest block-state treatment, not two**.
Phase 2 was therefore not started. BL-105, BL-106 and BL-021 remain filed and unbuilt.

## Plan-file work — all eight decisions recorded

| # | Decision | Where |
|---|---|---|
| 1 | **V1 is PRODUCTION** for real customers, not a pilot | PROJECT_PLAN.md "What V1 is" + four gate rulings |
| 2 | **Hosting**: V1 on our AWS, CloudFront default hostname; bank subdomain is V2 | PROJECT_PLAN.md "Hosting"; task **S8-13** filed |
| 3 | **R-014**: three missing states ship as-is | RISKS.md — status now **Accepted for V1** |
| 4 | **V2 scope**, six items, each with its V1 cost | PROJECT_PLAN.md "V2 scope" |
| 5 | **BL-083**: NOT decided — filed as an open decision | BACKLOG.md BL-083 |
| 6 | **BL-019**: format accepted, row closed | BACKLOG.md BL-019 |
| 7 | **BL-085**: headline corrected | BACKLOG.md BL-085 |
| 8 | **CLAUDE.md**: deferred-mobile-half comment rule | CLAUDE.md |

**"Pilot" is gone from the plan files** — 29 prose mentions replaced across seven files; one
session-report *filename* was protected, because a filename is an address, not a claim.
[OBSERVED]

**BL-085 verified before correcting it.** All three renderers now hold
`BANK_NAME_AR = "البنك السوداني الفرنسي"` (`OtpMessageRenderer.java:23`,
`ReviewMessageRenderer.java:22`, `SubmissionMessageRenderer.java:22`). Your reading of the row
holds; the headline was stale and now states the current fact. [OBSERVED]

**On CLAUDE.md's length:** the file went 233 → 239 lines. **It has no stated length limit** — the
150–250 line target at `CLAUDE.md:122` governs *session reports*, not CLAUDE.md itself. Nothing
was cut, because nothing was over. If you want a hard cap on that file, say so and I will apply
one.

---

# Needs your attention

## Three decisions for you to rule on

### A. Should the lifetime mint cap be escapable at all? (BL-114 cause (c))

**The situation.** Every profile may mint at most 20 scan tokens and 20 face tokens, ever. That
cap was added as an abuse bound and it works. What was never decided is what happens to a real
customer who hits it honestly — through repeated camera-permission problems, poor network, or a
Uqudo outage. Today there is no way back for them: no reset exists anywhere in the system, and
the app journey is closed for that account permanently.

**The options.** (1) Leave it permanent and treat a branch visit as the intended route out.
(2) Give operators a reset action in the back office. (3) Treat operator **manual completion** —
which already exists — as the official answer, and make the app say so.

**My recommendation: option 3, with the screen fixed to say it.** Manual completion already
exists, already has the four-eyes rule and an audit trail, and already resolves the customer's
actual problem (their data gets updated). No new write path, no new abuse surface. The real
defect is not the absence of a reset — it is that the screen **lies** to the customer instead of
directing them to the branch that can help. **What a reset would take, since you asked:** a new
backend endpoint plus repository write to zero the two counters, an operator control and
permission in the back office, and an audit event — the back office **cannot** invoke anything
like it today, as it has no path to those columns at all. **What option 3 costs if you pick it
and I am wrong:** manual completion is a *bypass* of Uqudo, face match and the Civil Registry —
R-018 already records that exposure — so making it the routine answer to a common failure widens
a hole that is currently rare. If capped customers turn out to be numerous, you would be
converting a digital journey into a manual one at scale.

### B. The profile UUID in column 1 of the operator export

**The situation.** The identity-image endpoint checks nothing but the profile UUID — hold the id,
get the passport and ID scans. Your R-051 deferral rests on the V1 group being controlled and
trusted. But the operator export writes that same UUID as **column 1 of every exported row**
(`ExportRow.java:41`), and an export is a file that travels — to a laptop, an email, a shared
drive. Anyone holding a copy can fetch every identity image it lists, without being in the
trusted group at all.

**The options.** (1) Leave the column. (2) Drop it from the export. (3) Keep it but restrict who
may export.

**My recommendation: drop the column (option 2), and it is close to free.** I checked before
telling you that: **nothing downstream consumes it.** There is no export UI in the back office at
all — BL-026 cut it from Phase 1, and the only reference to export in `backoffice/src` is a
comment. [OBSERVED] The only consumers are two places that both derive from the same `COLUMNS`
array and follow automatically, plus two tests. The export already carries the **reference
number**, which is the identifier operators and customers actually use. Change is backend-only
and contained. **What leaving it costs:** the export becomes a portable key-ring for identity
images, and because a served image cannot be unserved, that exposure is permanent from the moment
one file is mislaid — it does not end when V2 adds authentication. **What dropping it costs:** if
the bank later wants to join export rows back to profiles, the reference number is the join key
instead, and any consumer built on the UUID would need changing — none exists today.

### C. BL-083 — does V1 offer the national ID card?

**The situation.** The app still offers national ID as a document choice
(`stage7_screen.dart:184-187`, verified). [OBSERVED] That scan path has **never run end to end on
any tenant, with any real card**. Under the new definition of V1, that means offering real
customers a route that may simply not work.

**The options.** (1) Passport-only in V1, restore national ID in V2 once a card is tested.
(2) Ship both and accept the risk. (3) Delay V1 until a card is obtained and tested.

**My recommendation: option 1.** **Why the risk is not theoretical:** S7-12's Civil Registry
defect was passport-specific — the passport prints the national number **grouped**, and the
adapter forwarded it verbatim. The card's number format is **unobserved**, so the same class of
bug may be waiting, and it would surface as *"the registry has no record of you"* for **every
card holder**. **What option 1 costs:** every customer holding a card but no passport is excluded
from the digital journey for the life of V1 and must use a branch. In Sudan that is plausibly the
larger group, so this is a genuine reduction in reach, not a formality. **I have not decided
this** — BL-083 is filed as an open decision awaiting your ruling.

## BL-005 — the rejection copy, verbatim and in full

**Nothing here was changed.** Source: `backend/src/main/resources/db/migration/V0019__seed_rejection_reason.sql`,
lines 33–67 for every Arabic string; English source wording from `docs/journeys/operator.md`
lines 170–176.

**The Arabic customer message below IS the exact SMS body.** `ReviewMessageRenderer.java:46-47`
returns `customerMessageAr` verbatim as the SMS payload — no prefix, no wrapper, no template.
(The *email* subject alone adds the bank name; the SMS does not.) [OBSERVED]

**The seed file flags its own provenance gap** (`V0019:10-15`): operator.md supplied these
reasons in **English only**, and *"the Arabic text below is a translation made for this seed, not
sourced from the bank."*

| Code | Arabic internal label (`label_ar`, V0019) | English source (operator.md) |
|---|---|---|
| REJ-01 | صور المستندات غير واضحة أو رديئة الجودة | Document images illegible or poor quality |
| REJ-02 | تعارض تفاصيل المستند مع السجل المدني | Document details conflict with Civil Registry |
| REJ-03 | فشل أو عدم وضوح مطابقة الوجه | Face match failed or inconclusive |
| REJ-04 | شبهة تلاعب أو تزوير في المستند | Suspected document tampering or forgery |
| REJ-05 | المستند لا يخص صاحب الحساب | Document does not belong to the account holder |
| REJ-06 | بيانات مدخلة غير قابلة للاستخدام - نص حر غير منطقي أو متناقض | Entered data not usable — nonsense or contradictory free-text values |
| REJ-07 | أخرى - تفصيل داخلي إلزامي | Other — internal detail mandatory |

**The exact Arabic SMS body the customer receives** (`V0019`, `customerMessageAr`):

- **REJ-01** (`V0019:36`) — لم تكن صور مستنداتك واضحة بما فيه الكفاية. يرجى زيارة أي فرع مع مستندك الأصلي.
  - *English source (`V0019:37`)*: Your document images were not clear enough. Please visit any branch with your original document.
- **REJ-02** (`V0019:41`) — تعذر تأكيد بياناتك مقابل السجل المدني. يرجى زيارة أي فرع مع مستندك الأصلي.
  - *English source (`V0019:42`)*: Your details could not be confirmed against the Civil Registry. Please visit any branch with your original document.
- **REJ-03** (`V0019:46`), **REJ-04** (`V0019:51`), **REJ-05** (`V0019:56`), **REJ-07** (`V0019:66`) — all four share one message, deliberately: تعذر إكمال التحديث الخاص بك. يرجى زيارة أي فرع مع مستندك الأصلي.
  - *English source*: Your update could not be completed. Please visit any branch with your original document.
  - *Why they share it* (`V0019:19-20`, operator.md:178): naming which control fired would tell a fraudster what to change.
- **REJ-06** (`V0019:61`) — تعذر قبول بعض البيانات التي قدمتها. يرجى زيارة أي فرع لإكمال التحديث.
  - *English source (`V0019:62`)*: Some of the details you provided could not be accepted. Please visit any branch to complete your update.

## Where the profile UUID travels

**Answer: it also appears in these named places, each of which is internal** — with one that
deserves your attention (the export, decision B above).

**Created:** `gen_random_uuid()` — `V0005__app_status_and_profile.sql:29`. A **random v4**. It is
**not guessable and not enumerable**: no sequence, no timestamp, no account-derived structure.
[OBSERVED]

| Where it goes | Evidence | Assessment |
|---|---|---|
| The customer's own device | `session_database.dart:75`, in the encrypted local store | Never displayed, never logged by the app |
| **A URL query string** | `IdentityScanController.java:174-177` — `GET /api/v1/identity-scan/image/{kind}?profileId=` | The only place it enters a request line. The code knows: `:137-138` calls the surface "unauthenticated by design (R-051)" |
| **The operator's address bar** | `backoffice/src/App.tsx:41`, `ProfileDetailPage.tsx:108` — `/profiles/:profileId` | Internal. In browser history, and in any screenshot of the browser |
| **Column 1 of the operator export** | `ExportRow.java:41` | Internal, **but the file travels** — see decision B |
| Backend logs / CloudWatch | Exception text embeds it; ECS `awslogs` → `/ecs/<prefix>-backend`, 30-day retention (`08-backend-service.sh:109-117`, `:257-261`) | Internal |

**Where it does NOT go**, each checked rather than assumed:

- **No SMS, WhatsApp or email body.** No message renderer references `profileId` at all —
  customers receive the **reference number**, never the UUID. [OBSERVED]
- **No HTTP error body.** The controllers' problem-detail handlers use fixed literal strings, not
  the exception messages that contain the id. [OBSERVED]
- **Not in AWS access logs — but by default, not by design.** Neither CloudFront standard logging
  nor ALB access logs are configured (`09-backoffice-cloudfront.sh` has no `Logging` block; no
  ALB `access_logs` attribute anywhere in `infra/`). Both default to *disabled*. **This is worth
  knowing precisely because it is a default and not a control:** the id sits in a query string,
  so enabling either — an ordinary operations action — would begin capturing it. [OBSERVED]

**I have not designed a mitigation and have not treated any of this as a reason to reopen your
ruling.** R-051 stays deferred; decision B is put to you separately because you asked to re-rule
against the export fact specifically.

## Phase 2 entry gates — every item, under "V1 means real customers"

**You rule on all of these. Nothing below was fixed, mitigated or designed around.**

**The four you already ruled** (now recorded in PROJECT_PLAN.md with their exposure):

| Item | Ruling | What it exposes in V1 |
|---|---|---|
| **R-051** | Deferred to V2 | Passport and ID scans served to anyone holding a profile UUID; no customer auth anywhere on `/api/v1/**`; **a served image cannot be unserved** |
| **BL-072** | Deferred to V2 | The AWS account is rooted on a personal mailbox — that mailbox controls the account, **its KMS keys, and every backup of every identity document** |
| **BL-089** | **V1 BLOCKER** | **Confirmed at source:** `infra/aws/08-backend-service.sh:234` hard-codes `"FRU_CORE_BANKING_CLIENT": "stub"` in the ECS task definition. A real customer's real account number returns "not found" and the journey cannot start |
| **BL-082** | **V1 BLOCKER** for any Play Store route | Debug signing key, no AAB ever built, no hosted privacy-policy URL, no Data safety declaration |

**The rest of the list, which you had not reviewed.** Each is reported as what it exposes, who
owns it, and what closing it would take:

| Item | Status at source | What it exposes now that V1 is real customers | Owner | To close |
|---|---|---|---|---|
| **R-026** — encryption at rest | 🔴 Live. Provisioned on **staging** at S7-03 under a customer-managed KMS key; **production stack does not exist** | RDS storage encryption is an **at-creation** property — it cannot be added later. If the production instance is created without it, every identity document is at rest unencrypted and the only fix is a rebuild | Us (provisioning) | Get it right once, at production instance creation. Also see BL-072: the KMS keys live in the personally-rooted account |
| **R-037** — audit seals never exported | 🔴 Live | Seals are produced but stay in the same database they police, so they protect against nothing that compromises that database. AD-002d is explicit that a same-account S3 destination does **not** discharge this | Bank + us | S3 Object Lock (Compliance mode) in a **separate bank-owned account** — needs a bank account that does not exist yet |
| **R-046** — back-office image URLs | 🔴 Live | An `<img>` tag cannot carry an auth header, so the back office cannot display identity images under any scheme that requires one. Related to R-051 but **not the same problem**, and untouched by the AWS decision | Us (design) | A signed-URL or blob-fetch design decision, still open |
| **AD-002d remainder** | Provider closed (AWS); the rest **open** | The open half is exactly R-051 + R-026 + R-037 + R-046 above — the hosting decision closed *which host*, not *whether the surface is safe* | Us | The four rows above |
| **AD-002b / AD-003** | Real integrations | Named in the phasing paragraph as production prerequisites | Bank + us | Scoped separately |
| **OQ-001** — regulatory regime | **OPEN** | **This is the one I would put highest of the unruled items.** The legal position for processing real identity documents at all — Bank of Sudan requirements, consent basis — is unanswered. AD-002d states explicitly that the AWS decision does **not** answer it, and that a Sudan-based bank's use of a US cloud provider (sanctions, data residency) is **the bank's legal team's call**. V1 puts real identity documents into that account | **Bank's legal/compliance** | A ruling from the bank, before real customer data lands |
| **OQ-002** | **CLOSED 2026-09-07** — out of scope for this delivery | Nothing outstanding | — | — |

**One thing I would add to this list that was not on it:** **BL-114**, the finding above. It
permanently burns a real customer's account and is now, under this definition of V1, a
customer-facing production defect rather than a tester inconvenience.

---

# Carried forward

## Phase 2, untouched — the whole of it

**BL-105** (salary certificate never uploaded; the customer is told «تم إرفاق» and the file is
deleted), **BL-106** (approve/reject have no in-app consequence), **BL-021** (phone-locked
customer blamed for their internet). All three remain filed, unbuilt, and blocking the V1
release. **BL-114 joins them**, and BL-021's fix should now be designed together with BL-114's
block screen rather than separately.

**What the next session takes:** the three Phase 2 fixes plus BL-114, once you have ruled on
decision A — because A decides what BL-114's block screen is allowed to say, and BL-021 shares
that screen's design. Ruling on A first avoids building the screen twice.

## Gates

**Say plainly: the backend coverage gate did not run.** `./mvnw verify -Pdb-integration-test` —
the only gate that actually proves backend coverage — **could not be run because the Docker
daemon is not running on this machine**. Per CLAUDE.md, plain `./mvnw verify` measures ~60% and
does not clear the 80% threshold on its own, so running it would have produced a *failure* that
proves nothing about this session. **No backend source was changed this session**, so no backend
coverage regression is possible — but that is an argument from the diff, not a gate result, and
it should not be read as one.

## The pending product-owner gates — nothing new opened

**No new Arabic strings and no new screens or controls were created this session.** All work was
documentation and plan files, so:

- **Native-Arabic review gate** — unchanged. Still holds the 134 Uqudo override strings and the
  S8-10 strings. **Nothing added.**
- **Device pass** — unchanged. Still one pending pass. **Nothing added.**

The Arabic quoted in this report is **existing** copy reproduced for your review (BL-005) or
quoted as evidence (BL-114's false "try again now" string). None of it is new.

## Out of scope, untouched as instructed

iOS in every form · BL-104 and BL-103 · the 54 unexamined backlog rows · the back-office tier ·
AD-011 · WhatsApp and email messaging · reference-data update mechanisms · rejection-reason
wording · and any fix, mitigation or workaround for a Phase 2 entry gate.

---

## Gate output — verbatim

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 34.9s)
```

```
$ fvm flutter test
01:54 +540: All tests passed!
```

```
$ fvm dart run tool/check_coverage.dart
02:34 +540: All tests passed!
Line coverage: 84.97% (3896/4585 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

```
$ npm run test:coverage
=============================== Coverage summary ===============================
Statements   : 97.09% ( 501/516 )
Branches     : 86.25% ( 320/371 )
Functions    : 97.46% ( 154/158 )
Lines        : 98.9% ( 452/457 )
================================================================================
```

```
$ ./mvnw test
[INFO] Tests run: 903, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```
$ ./mvnw spotless:check
[INFO] BUILD SUCCESS
[INFO] Total time:  2.274 s
```

```
$ ./mvnw verify -Pdb-integration-test
NOT RUN -- Docker daemon unavailable on this machine:
  failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
No backend source was changed this session.
```

`npm run lint` passed with pre-existing warnings only (exit 0) — an intermediate run, reported
as one line per the rules.

```
$ fvm flutter build apk --release --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net
Running Gradle task 'assembleRelease'...                           13.2s
√ Built buildpp\outputslutter-apkpp-release.apk (108.2MB)
```

The release build also emitted the known AGP 8.11.1 / Kotlin 2.2.20 deprecation warnings — that
is **BL-003**, already filed, and not introduced here. The APK is **debug-signed**, which is
**BL-082**, ruled a V1 blocker for any Play Store route above.

---

## Untracked files before committing

Reported before staging, per R-053 (`git add -A` at 90ab089 swept 91 unrelated files into
`main`). `git status --porcelain` immediately before the commit:

```
 M BACKLOG.md
 M CLAUDE.md
 M EXECUTION_PLAN.md
 M PROJECT_PLAN.md
 M RISKS.md
 M docs/journeys/customer.md
 M docs/road-to-production.md
 M docs/sessions/2026-09-10-audit-session-report.md
?? docs/sessions/2026-09-11-dead-ends-and-v1-release-blockers.md
```

**Exactly one untracked file** — this report. The eight modified files are the plan files, the
journey document, and the one-sentence correction to the S8-11 report. The 108 MB release APK
this session built is correctly ignored and does not appear. Nothing unrelated is being swept in.

---

## Commit proof

**The work commit**, carrying every change described in this report:

```
$ git log --oneline -1
d8025d1 S8-12: four dead ends verified, phase gate fired, eight decisions recorded
```

```
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

**CORRECTED 2026-09-11 at S8-14.** The block above originally pasted a `git status` **captured
before the push**, so it read "ahead of 'origin/main' by 1 commit" while the surrounding text
claimed both commits were pushed. The claim was true — `d8025d1` and `bc64fe5` are both ancestors
of `origin/main`, re-verified at S8-14 with `git merge-base --is-ancestor` — but the evidence
pasted did not show it, and evidence that contradicts its own claim is worth nothing. CLAUDE.md
now carries the rule this violated: **commit proof is captured after the push, never before.**

Branch `main`, tracking `origin/main` (`git rev-parse --abbrev-ref --symbolic-full-name @{u}`
→ `origin/main`), working tree clean.

**On why there are two commits and not one.** A report cannot contain the hash of the commit that
creates it — the hash does not exist until the commit does. So `d8025d1` carries the work, and
**this proof section is added by the commit immediately following it**; both are pushed to
`main` together, and `git log --oneline -2` shows the pair. Nothing here is a placeholder and
nothing is promised that does not already exist by the time this is read — which is the failure
mode 1b9d067 hit and f9fd768 half-corrected, and which the first section of this session fixed.

