# S8-24 — BL-136: the operator can see the salary certificate

**Date:** 2026-09-13 · **Tiers:** backend + backoffice
**Outcome:** BL-136 closed. Six viewable kinds, not five. A PDF certificate opens in a modal.

Wayfinder ticket 02 opened by saying six artifact kinds carry bytes and listed six. There are
seven — `JdbcSalaryCertificateRepository` writes a `body` for `salary_certificate` exactly as the
others do — so when the ticket ruled "the set is the set" it had never weighed the seventh. The
exclusion was an arithmetic slip, not a decision. S8-23 then deleted the metadata attachments table
and made the document invisible in every surface the system has.

**Product owner, this session: yes, an operator may view it.** Reasoning recorded on BL-136 rather
than left implicit — the operator approves a profile stating an income source and a monthly income,
and the certificate is the evidence for those fields.

---

## What shipped

| Layer | Change |
|---|---|
| `operator/domain/OperatorImagePolicy.java` | `salary_certificate` added; type allow-list now **per kind** |
| `operator/service/OperatorImageService.java` | passes `kind` to `pinContentType` |
| `auth/config/SecurityConfiguration.java` | chain 1: `X-Frame-Options: SAMEORIGIN` + `frame-ancestors 'self'` |
| `profiles/artifactTiles.ts` | sixth kind, caption «شهادة المرتب», `isPdf` |
| `profiles/ArtifactContactSheet.tsx` | `DocumentTile` + PDF modal; kind-aware empty state |
| new `OperatorImageHeadersIntegrationTest` | the headers no other test of this route can see |

The certificate is **last** in render order: identity evidence first, income evidence after.

---

## The row's own estimate was wrong, and that is the substance of this session

BL-136 called the fix "a one-line change plus a test". It is not.

`SalaryCertificateService:42-43` accepts `application/pdf` alongside the two image types, and
`mobile/lib/features/dataentry/salary_certificate_field.dart:89` offers `pdf` in the picker and
stores a picked PDF byte-identical. `OperatorImagePolicy`'s type allow-list was JPEG and PNG. So
adding the KIND alone would have gone on 404ing the likeliest real certificate — a payslip is at
least as often a PDF as a photograph — while looking, from the kind set, entirely fixed.

**`pinContentType` is now scoped per kind.** `application/pdf` is reachable only through
`salary_certificate`; the five identity kinds stay images or nothing. A single widened list would
have let any artifact be served as a PDF the moment its `content_type` column said so, and that
column is declared by whoever wrote the row — the reason `OperatorImagePolicy` exists at all. An
integration test seeds a `doc_front` falsely declaring `application/pdf` and asserts the 404 plus a
zero audit count.

---

## The blocker the review caught, which both suites would have shipped green

**Spring Security serves `X-Frame-Options: DENY` on this route, so the PDF modal could never have
rendered in a real browser.**

`SecurityConfiguration` never called `headers(...)`, and there is no header configuration anywhere
in the repo — verified by grep across `backend/` and `infra/`. Spring Security applies
`headers(withDefaults())` to every chain it builds and `FrameOptionsConfig` defaults to `DENY`,
which blocks framing **even same-origin**. An operator would have clicked «شهادة المرتب» and got
the browser's "Refused to display" message where the pay document should be. The whole deliverable.

**Why nothing caught it.** Every existing test of this endpoint runs
`@AutoConfigureMockMvc(addFilters = false)` (`OperatorImageIntegrationTest:63`) so it can inject an
operator identity directly — which also means the security filter chain, and every header it
writes, never ran. And jsdom never fetches an `<iframe>`'s `src`, so no front-end test could see it
either. A backend suite of 1,130 green tests and a backoffice suite of 162 green tests, on either
side of a feature that does not work.

**The fix:** `frameOptions().sameOrigin()` plus `Content-Security-Policy: frame-ancestors 'self'`
on chain 1. Scope in Spring Security is per chain rather than per route, so this covers all of
`/api/v1/operator/**`, `/auth/**` and `/admin/**` — acceptable because every response on that chain
is JSON or artifact bytes, never an interactive page a clickjacking attack would have a click to
steal. The SPA's own HTML is not served by this application at all (CloudFront's default behaviour
serves it from S3), so its framing posture is untouched.

**The proof is a test, not a live capture, and deliberately so.**
`OperatorImageHeadersIntegrationTest` runs WITH the filters and asserts `SAMEORIGIN` literally.
That is a direct wrong-value assertion: it cannot pass against the `DENY` default, so no
revert-restore is owed. Its second case asserts chain 2 — the customer-facing endpoints — still
carries `DENY`, so the relaxation cannot later be read as "framing is fine everywhere".

---

## Review findings and dispositions

`@agent-reviewer` ran against the diff and returned ten findings. All accepted, all fixed.

| # | Finding | Disposition |
|---|---|---|
| 1 | **BLOCKER.** `X-Frame-Options: DENY` blocks the PDF modal's iframe; no test of the route runs the filter chain. | **Fixed** in `SecurityConfiguration`, with a new filters-enabled test. Above. |
| 2 | `isPdf` tested only the declared type, not the kind — so a `doc_front` whose column claimed `application/pdf` (the exact threat this design names) would render a document tile where the passport should be, then frame a URL the server 404s into a blank modal with nothing saying anything was wrong. | **Fixed.** `isPdf` now requires `kind === 'salary_certificate'`, mirroring the server. Every other kind falls through to the `<img>` + `onError` → broken-tile path. A new test seeds the lying `doc_front` and pins it. |
| 3 | A comment claimed an image declared `application/pdf` "is refused by the server". For `salary_certificate` it is not — the policy allows PDF for that kind from the column alone and deliberately does not sniff. | **Fixed.** The comment now says what actually happens in each mismatch direction. |
| 4 | `OperatorImagePolicyTest` claimed the kind is refused "in two independent places". They are not independent — the SQL binds `viewableKinds()`, the same set the policy checks, so one edit opens both. | **Fixed.** Reworded: the redundancy buys defence against a read path that forgets the filter and avoids de-TOASTing a discarded body. The gate that genuinely takes a second edit is the per-kind type list. |
| 5 | Five surviving "five kinds" / "`salary_certificate` 404s" claims: `api/types.ts`, `artifactTiles.ts` (×2), `ArtifactContactSheet.tsx` (×2), `ProfileDetailPage.tsx`, ticket 08. | **Fixed,** all of them. `ProfileDetailPage.tsx` was the sharpest: my own line from this morning said the sheet links six kinds "straight into `<img>` elements", and the sixth deliberately is not one. |
| 6 | The empty tile said «لم يصل الملف إلى هذه المرحلة بعد» for a missing certificate. It is OPTIONAL and gates nothing, so on a submitted profile its absence usually means the customer declined — a finished state. The copy claimed a future that never arrives. | **Fixed.** «لم يُرفق (اختياري)» for that kind only, with a test. It still cannot distinguish declined from upload-failed; that is BL-122, open, and the copy is deliberately neutral. |
| 7 | Inline same-origin PDF posture: acceptable. | **No change.** Recorded below. |
| 8–10 | Iframe clean; kind filter genuinely defence in depth; test strength improved; fixtures and plan files sound apart from finding 1's consequence. | **Noted.** |

---

## A defect found by a test I wrote to cover a line

The close-the-modal test failed, and it was right to. `rc-dialog` wraps a closed modal's children in
`MemoChildren` and stops re-rendering them, and `destroyOnHidden` only takes effect once the leave
MOTION completes — which in jsdom never happens, and in a browser is merely late. So
`<Modal open={false}>{doc && <iframe/>}</Modal>` kept the iframe, and the customer's pay document,
mounted in a hidden subtree for the life of the page.

Fixed by making the **modal itself** conditional rather than only its children, so React drops the
whole subtree the moment the state clears. Simpler than the alternative and with nothing left to
reason about. The test now asserts `querySelector('iframe')` is null after closing.

---

## Security posture, stated rather than assumed

The reviewer examined serving an untrusted customer-uploaded PDF inline from the API origin and
called the risk acceptable. Recording the reasoning, because it is load-bearing and not obvious:

`Content-Disposition: inline` plus `X-Content-Type-Options: nosniff` plus a type pinned from the
policy and never echoed from the column means an HTML payload uploaded as a `salary_certificate` is
served as `application/pdf` and cannot be parsed as HTML, so no script runs on the origin where
`XSRF-TOKEN` is JS-readable and `JSESSIONID` rides automatically.

The residual, unmitigated and accepted: the upload path is unauthenticated and validates nothing
beyond size and declared type, so the framed bytes are wholly attacker-controllable and the
browser's own PDF viewer is the only thing parsing them. It runs PDF JavaScript without DOM access
to the embedder. `attachment` was rejected as the alternative — it would leave an unencrypted copy
of a customer's pay document in an operator's downloads folder, a data-protection regression the
rest of this design avoids. A new tab was rejected too: ticket 01 binds the artifact id out of
browser history and out of any referrer, and a tab puts it in both.

---

## Names deliberately not churned

`ProfileImageController`, `OperatorImage` and the `profile_image_viewed` audit event type all keep
their names though a PDF is not an image. Renaming the event type would split one action's audit
trail across two names and `audit.audit_event` already carries rows under the existing one. Stated
in the javadoc so the next reader finds the reason rather than the inconsistency.

## Raised, not settled

Ticket 04 fixed the printed form's images at both portraits and the signature, no document scans,
and never considered the salary certificate — **the same blind spot ticket 02 had, and BL-136 has
now shown that blind spot produced a wrong answer once.** The product owner has ruled on the SCREEN.
Whether income evidence is also printed is a different question (the print is internal, stored as
its own artifact, and leaves the screen), and it is ticket 04's to answer. Noted on BL-132.

---

## Gates

Backend — `./mvnw verify -Pdb-integration-test`, verbatim:

```
[INFO] Results:
[INFO]
[INFO] Tests run: 1132, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 468 files clean - 0 needs changes to be clean, 0 were already clean, 468 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 339 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

Backoffice — `npm run lint` exit 0, ten pre-existing warnings, none in changed files.
`npm run test:coverage`, verbatim tail:

```
 Test Files  20 passed (20)
      Tests  164 passed (164)

 % Coverage report from v8
-------------------|---------|----------|---------|---------|-------------------
File               | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s
-------------------|---------|----------|---------|---------|-------------------
All files          |   97.21 |     86.4 |   97.59 |   98.95 |
 src/api           |   98.14 |    94.11 |     100 |     100 |
  reference.ts     |   95.74 |       70 |     100 |     100 | 75-81
 src/auth          |     100 |    98.52 |     100 |     100 |
  AuthContext.tsx  |     100 |       90 |     100 |     100 | 57
 src/layout        |     100 |       80 |     100 |     100 |
  AppShell.tsx     |     100 |       75 |     100 |     100 | 33-59
 src/profiles      |   95.32 |     81.7 |   96.22 |   97.95 |
  ...leteModal.tsx |   83.33 |      100 |      80 |   83.33 | 43
  ...etailPage.tsx |   92.15 |    80.39 |   94.59 |    97.7 | 590-594
  ...eListPage.tsx |   97.64 |    73.91 |     100 |     100 | 109-145,171
  RejectModal.tsx  |      92 |    94.11 |    90.9 |   91.66 | 55-56
  detailLabels.ts  |     100 |     87.5 |     100 |     100 | 70,81
-------------------|---------|----------|---------|---------|-------------------

=============================== Coverage summary ===============================
Statements   : 97.21% ( 523/538 )
Branches     : 86.4% ( 324/375 )
Functions    : 97.59% ( 162/166 )
Lines        : 98.95% ( 473/478 )
================================================================================
```

`npm run build` succeeded (`✓ built in 5.71s`).

Intermediate runs after the review pass: `OperatorImageHeadersIntegrationTest` alone, 2 tests,
passed; `npx vitest run src/profiles/ArtifactContactSheet.test.tsx`, 13 tests, passed.

**Two runs failed and are kept as evidence.** The close-modal test failed three times before the
`MemoChildren` cause was found, which is the defect above. And a negative assertion written as
`queryByRole('button', {name: 'وثيقة الهوية'})` failed because antd's preview mask is itself a
button — retargeted at the document tile's own PDF marker, which only the PDF path renders.

---

## No real data

Every fixture is synthetic: `"%PDF-1.7 synthetic-not-a-real-document"`,
`"synthetic-certificate-bytes"`, accounts 0000000583–0000000585 (a fresh range; 581 and 582 were
already claimed further down the same class, and `AbstractPostgresIntegrationTest`'s registry was
widened to 585 to record it). No account number, national number, image or document from any real
person, and nothing read from the dev database.

---

## Commit proof

Captured AFTER the push, per CLAUDE.md. Single commit, straight to `main`, no branch.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   1e29e58..a478ae1  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	docs/bank-hosting-specification.md

nothing added to commit but untracked files present (use "git add" to track)
```

`docs/bank-hosting-specification.md` predates this session and is deliberately left untracked
rather than swept into this commit.
