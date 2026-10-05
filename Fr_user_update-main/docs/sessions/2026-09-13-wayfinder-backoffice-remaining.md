# 2026-09-13 — S8-18: wayfinder charting, the back office's remaining half

**Planning session. No code in any tier changed — only Markdown.** No test or analyze gate applies:
the rule is "run the gates for every tier you touched", and no tier was touched. `git diff --stat`
at the end of this report is the proof of that claim, not an assertion of it.

Scope: six items the product owner listed as remaining on the back office. Charted as a
`/wayfinder` map at `.scratch/backoffice-remaining/` — nine decision tickets, three resolved this
session.

Visual companion (published artifact, private):
https://claude.ai/code/artifact/785b4cfe-afe3-414c-966d-ccc4ce081dc6

---

## 1. The two items that were not what the brief assumed

Checked against source before any charting. This is the session's highest-value output, because
both would otherwise have been built a second time.

| Brief item | Reality |
|---|---|
| 6 — "backoffice decisions should trigger decision message to the user via verified communication channels" | **Already built.** `OperatorReviewService.approve`/`.reject` (l.147, l.239) and `ManualCompletionService` (l.194) each `notificationOutboxRepository.enqueue(...)` for every channel whose `ChannelState == VERIFIED`, **inside the same transaction as the status write**, so a decision cannot land without its message queued. `ReviewMessageRenderer` renders per-channel Arabic SMS / WhatsApp template / email. `OutboxDispatchScheduler` + `OutboxDispatcher` drain it. **Ruled out of scope on the map.** |
| 4 — "add to the audit the backoffice user activity" | **Already built for every surface that exists.** Eleven event types reach the append-only hash-chained `audit` schema with its own operator chain (V0047, V0058): `sign_in_succeeded`, `sign_in_failed`, `sign_out`, `password_changed`, `profile_list_searched`, `profile_viewed`, `profile_exported`, `profile_approved`, `profile_approve_refused`, `profile_rejected`, `profile_manually_completed`. **Reframed:** not a work item, a clause on the three new surfaces. Became ticket 09 / BL-133. |

The remaining four were real. Item 2's images are *stored* already (`doc_front`, `portrait_uqudo`,
`portrait_registry`, `face_audit_trail`, `signature` — all with bytes, confirmed on the S7-12 walk);
what was missing was any operator-facing endpoint, blocked on R-046.

---

## 2. Decisions taken

### R-046 CLOSED — by refuting its own premise, not by choosing between its two horns

R-046 framed the image-viewing question as **signed URL vs authenticated blob**, on the basis that
"an `<img>` tag cannot carry an Authorization header".

True, and irrelevant here. The back office has never used Authorization headers — it uses a
`JSESSIONID` session cookie — and **S7-08 made the back office same-origin with the API**: one
CloudFront distribution, default behaviour → private S3 via OAC, `/api/*` → the internal ALB over a
CloudFront VPC origin (`infra/aws/09-backoffice-cloudfront.sh`). A same-origin
`<img src="/api/v1/operator/…">` sends that cookie by itself.

**Decision: an ordinary cookie-authenticated `GET` rendered straight into an `<img>`.** No signed
URLs, no blob plumbing, no new key material.

Why this is the secure option on the current AWS hosting, not merely the cheap one:

1. **The audit stays honest** — every view is an origin request, so the event fires at *view* time.
   That is the precise property R-046 said a signed URL destroys.
2. **No second copy of the PII** — bytes live in Postgres (AD-004). A CloudFront signed-URL scheme
   would require copying identity documents into S3: a second store of passport images at rest,
   with its own encryption, lifecycle and deletion story. A data-protection regression bought for
   nothing.
3. **Nothing caches in front of it** — `/api/*` already uses the CachingDisabled cache policy
   (`4135ea2d-…`) and the AllViewer origin request policy (`216adef6-…`).
4. **Revocation is immediate** — `OperatorIdentityFilter` re-reads the account per request. An
   issued signed URL would keep working regardless.
5. **It survives the move to bank hosting**, which the product owner has deliberately deferred. A
   cookie-authenticated endpoint is portable; CloudFront key groups are not.

**The lesson worth keeping, recorded on the row itself:** R-046 was written before S7-08 settled the
origin, and nobody went back to re-read it. A risk row's premise can be silently invalidated by an
unrelated session.

**Consequences:** R-046 → ✅ Retired. BL-075 unblocked, with four binding build requirements added
(browser `Cache-Control: no-store`; per-profile artifact authorization — the real vulnerability
class here is IDOR, not transport; read through `app.artifact_read()`; pinned content type +
`nosniff`). `app.artifact_ref.storage_key` is now **dead** — deliberately not dropped, since that is
its own migration and its own review.

### AD-013 — admin is a superuser; four-eyes removed

Product-owner decision. viewer = view only, no print, no edit · operator = view, print, approve,
reject, manually complete · **admin = everything an operator may, plus sole authority to create and
manage back-office users**, through the UI.

**This reverses AD-002e's "admin is not a back-office access level".** AD-002e is *not* edited — it
carries a superseded marker and its reasoning and 403-for-admin proof stay readable. Three things in
the code currently say the opposite and must change: `operator_role.is_back_office` is false for
admin (V0057); each account holds exactly one role, making admin structurally exclusive of operator;
and `/api/v1/operator/**` gates on `ROLE_VIEWER`, which an admin never holds.

**Four-eyes removed entirely**, keeping `app.profile_status_history.is_manual_completion` so the
rule can be switched back on without a migration and with no blind spot for profiles completed
meanwhile. It was narrower than its name: it never touched reject, and never touched a
mobile-submitted profile.

**The argument that decided it, stated because it is not obvious:** an admin creates accounts and
sets their initial passwords, so an admin could *already* create a second account, manually complete
a profile as it, and approve it as themselves. Four-eyes was defeatable by one person before this
decision. Granting admin operator powers removes the last friction rather than opening the hole.

**Filed as R-054 🔴, not glossed:** no separation of duties now remains in the product. The audit
trail is the sole compensating control, which is what makes BL-133 load-bearing rather than tidy-up.

### AD-014 — Apache FOP 2.11 for the Arabic PDF

`@agent-researcher` pass (CLAUDE.md's third-party gate), report at
`docs/sessions/2026-09-13-research-arabic-pdf-toolchain.md`.

**Apache FOP 2.11, XSL-FO → PDF, embedding the IBM Plex Sans Arabic `.ttf` the repo already
carries.** The only Apache-2.0 candidate documented by its own project to apply the font's OpenType
GSUB/GPOS tables rather than pre-shaping into the U+FE70 compatibility block that Unicode itself
says must not be used for interchange.

- **iText ruled out on licence** — AGPLv3 forbids closed-source network deployment in iText's own
  words; pdfCalligraph is commercial-only with a deployed key. A procurement line item, not a
  footnote.
- **OpenPDF is clean on licence but loses on evidence** — LibrePDF/OpenPDF#779, open and
  unanswered: enabling RTL layout renders English right-to-left too. That is our exact requirement
  (Arabic labels beside Latin account numbers) failing upstream.
- **Runner-up headless Chromium** renders better but costs a ~400 MB browser and its CVE stream in
  the Fargate image, a subprocess on a one-render path, and the documented Fargate
  `sharedMemorySize` restriction — for throughput we do not need at ~3 submissions/hour.

**NOT YET PROVEN BY A RENDER.** The agent had no shell. §8's five-check acceptance spike must run
and be read by someone who reads Arabic *before any form layout is written* — if FOP mis-shapes, the
layout is discarded with the library, because XSL-FO does not port to HTML. Recorded on AD-014 and
BL-132 rather than left as an assumption.

Two traps carried out of the report into BL-132: `mobile/lib/core/text/ltr_value.dart`'s FSI/PDI
idiom **must not** be copied into the renderer (`fop-core`'s `BidiConstants` defines no isolate
classes — FOP's bidi predates Unicode 6.3), and the FO must be **generated with escaping, never
concatenated**, since every field on the form is customer-supplied.

---

## 3. Review findings and dispositions

`@agent-reviewer` was run against the full diff, scoped to whether each factual claim holds against
the source it cites, cross-document id/reference integrity, and Markdown table cell counts.

**Verified correct, no finding** (each checked against source): same-origin + CachingDisabled
(`4135ea2d`) + AllViewer (`216adef6`) at `09-backoffice-cloudfront.sh:259-263`; session-cookie auth
with no Authorization header anywhere; **four-eyes has no DB enforcement** — every hit in every
migration is a comment or commented-out illustrative SQL, the only non-comment hit being the
`is_manual_completion` column declaration (V0009:16), so AD-013/BL-131's "no migration needed"
premise is sound; all eleven audit event types exist; AD-004 stores no bytes for the frame kinds;
`created_by` and `is_back_office` as claimed; AD-012 fork 2/5 accurate; table cell counts and id
uniqueness clean; no secrets or real data (one synthetic `+249…` inside a bidi explanation).

**Eleven findings, all accepted and fixed in this session.** The two that mattered most:

| # | Finding | Disposition |
|---|---|---|
| 1 | **`auth/web/OperatorIdentityFilter.java:53` filters admin out** — `.filter(account -> account.role() != OperatorRole.ADMIN)`. An admin never receives an `OperatorIdentity`, so `OperatorIdentityArgumentResolver` refuses every operator endpoint **regardless of what SecurityConfiguration allows**. This is the single change without which "admin is a superuser" does not work, and neither AD-013 nor ticket 06 named it. Also missed: `OperatorAccessLevel` is a two-valued enum with no admin mapping, and `OperatorRole` l.5-9's javadoc asserts the now-false "an admin account never receives an `OperatorIdentity` at all". | **Fixed.** All three added to AD-013 and ticket 06. Worth stating plainly: the security chain is the obvious place to look and it is *not* the binding one. |
| 2 | **A third four-eyes test was missing from the blast radius** — `auth/OperatorAuthenticationIntegrationTest:189` `fourEyesWithRealIdentitiesSurvivesARenameBecauseActorIdIsTheUuid` drives a real sign-in, manual-completes, then asserts 403 on the same operator's approve. It **fails the build**, it does not merely go unasserted. | **Fixed.** "two tests" → three, in BL-131 and ticket 06. |
| 3 | `R-054` was appended to RISKS.md's *"Delivery notes — outside the deliverable"* table, whose own prose says those rows are "not risks to what we build". R-054 plainly is. | **Fixed** — moved into the active register (now l.57, before the l.65 heading). |
| 4 | `R-018` 🔴 Live still names four-eyes as an active mitigation. | **Fixed** — clause struck, pointed at R-054. |
| 5 | `BL-075` contradicted itself across two cells: cell 2 said UNBLOCKED, cell 3 still said "cannot be built until R-046 is decided". | **Fixed.** |
| 6 | `PROJECT_PLAN.md` AD-002d's bullet still listed R-046 as "still to be made". | **Fixed** — and the irony recorded: the same-origin posture in that very bullet is what closed it. |
| 7 | **Seven in-source comments plus a live column COMMENT freeze "R-046 still open"** — five of them in features BL-075 will never open. | **Fixed** — all eight paths assigned to BL-075 explicitly, per CLAUDE.md's frozen-comment rule. |
| 8 | `ReviewRepository.java:60`'s `isManualCompletionActor` — the **port**, not just the JDBC adapter — was missing from the blast radius; and `ReviewController` was filed under `canApprove` when its real dependency is the `FourEyesViolationException` catch. | **Fixed** in BL-131 and ticket 06. |
| 9 | `OperatorReviewService:278` writes `"reason": fourEyesViolation ? … ` into `profile_approve_refused`, and `ManualCompletionService:41`'s javadoc justifies that event solely by four-eyes. | **Recorded, not decided** — named in BL-131 as ticket 09's call, explicitly not to be deleted by inference. |
| 10 | Ticket 02 said "Five artifact kinds" over a list of six. | **Fixed.** |
| 11 | Ticket 03 claimed the project uses "Noto-family faces" — there is no Noto reference in the repo. | **Fixed** — harmless, since the research report itself listed only the faces that exist, but the ticket text stood uncorrected. |

Two stale references *outside* the diff were also corrected rather than left to be found:
`docs/journeys/journey-open-items.md:171` mirrored R-018's dead four-eyes clause, and
`docs/road-to-production.md:406` carried "**4.7 The four-eyes rule has never been tested**" as a
production gate — now marked moot, discharged by deletion rather than by a passing test.

---

## 4. What was deliberately NOT done

- **No production code.** Wayfinder plans; it does not build. Every decision above is recorded for a
  build session, and nothing in `backend/`, `backoffice/` or `mobile/` was touched.
- **`storage_key` not dropped.** Dead, recorded as dead, left in place — dropping a column is its
  own migration and its own review.
- **`docs/bank-hosting-specification.md` not committed.** Untracked before this session began, not
  this session's work, deliberately left out of the commit (R-053: never `git add -A`).
- **PDF/A conformance not investigated.** Flagged by the research as worth asking the bank *now*
  because retrofitting changes font-embedding rules. Carried as fog on the map, not decided in
  passing.

---

## 5. Map state at session end

Nine tickets at `.scratch/backoffice-remaining/`. Resolved: **01** (image addressing), **03** (PDF
toolchain), **06** (admin + four-eyes). Open and unblocked: **02** (which artifacts the operator
sees), **04** (the form's field set), **05** (printed-artifact lifecycle), **07** (admin screen
capabilities — unblocked by 06). Blocked: **08** on 04, **09** on 01/05/07.

Fog, deliberately not ticketed: what replaces four-eyes before production; PDF/A; BL-084's infinite
30 s outbox retry; BL-026's missing export button; whether the viewer role survives; the operator
dashboard.

---

## 6. Gate output and commit proof

**No gate was run, and none was owed.** CLAUDE.md requires the test and analyze gates "for every
tier you touched". No tier was touched — `git diff --cached --stat` below is the evidence, not the
claim: eighteen files, every one Markdown, nothing under `backend/`, `backoffice/` or `mobile/`.

```
 .../issues/01-image-addressing.md                  | 111 +++++
 .../backoffice-remaining/issues/02-which-images.md |  32 ++
 .../issues/03-arabic-pdf-toolchain.md              |  84 ++++
 .../issues/04-form-field-set.md                    |  40 ++
 .../issues/05-printed-artifact-lifecycle.md        |  50 +++
 .../issues/06-does-admin-become-a-superuser.md     | 117 +++++
 .../issues/07-admin-screen-capabilities.md         |  42 ++
 .../issues/08-ui-and-print-design-pass.md          |  52 +++
 .../issues/09-audit-events-for-the-new-surfaces.md |  43 ++
 .scratch/backoffice-remaining/map.md               |  93 ++++
 BACKLOG.md                                         |  10 +-
 EXECUTION_PLAN.md                                  |   1 +
 PROJECT_PLAN.md                                    |   6 +-
 RISKS.md                                           |   5 +-
 docs/journeys/journey-open-items.md                |   2 +-
 docs/road-to-production.md                         |   2 +-
 .../2026-09-13-research-arabic-pdf-toolchain.md    | 469 +++++++++++++++++++++
 .../2026-09-13-wayfinder-backoffice-remaining.md   | 196 +++++++++
 18 files changed, 1346 insertions(+), 9 deletions(-)
```

Staged **explicitly by path, never `git add -A`** (R-053). `docs/bank-hosting-specification.md` was
untracked before this session began and is deliberately left untracked.

Commit proof is appended below **after** the push, per CLAUDE.md — a status reading "ahead of
origin/main" would disprove the push, not prove it.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   b378131..37932d9  main -> main

$ git status -sb
## main...origin/main
?? docs/bank-hosting-specification.md

$ git log --oneline -1
37932d9 S8-18: wayfinder map for the back office's remaining half

$ git rev-parse HEAD origin/main
37932d91694b751439036274e66cd2aa9ca236f0
37932d91694b751439036274e66cd2aa9ca236f0
```

`## main...origin/main` with no ahead/behind marker, and HEAD identical to
`origin/main`, is the push proof. Committed straight to `main`, this project's norm — no branch.
