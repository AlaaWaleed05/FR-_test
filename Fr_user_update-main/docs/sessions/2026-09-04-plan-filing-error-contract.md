# Filing the identity-scan error-contract investigation into the plan files

**Date:** 2026-09-04
**Type:** documentation only — no source file, test or migration touched, so no gates apply.
**Session output proper:** `docs/sessions/2026-09-04-research-identity-scan-error-contract.md`.
This note records only what was filed and where.

---

## Numbering, checked against disk before any edit

| Expected by the task | Found on disk | Action |
|---|---|---|
| BL-033 exists (eight-way 409 collapse) | ✅ `BACKLOG.md:37` | Enriched in place, same ID |
| R-051 exists (image exposure) | ✅ `RISKS.md:56`, active register | Untouched |
| Highest BL is 033, so BL-034 free | ✅ | BL-034 created |
| R-047 free | ❌ **R-047 is taken** — an existing RETIRED risk at `RISKS.md:55` (the `face_reference_image` row, retired at S5-06). The register runs to R-051 | **Adjusted to R-052**, the first free number |

The R-047 collision is the one deviation from the task as written, and it is reported
rather than silently absorbed: reusing a retired ID would have broken RISKS.md's own
"IDs are R-### and are permanent, never renumbered" rule.

---

## What was filed

**1. `docs/sessions/2026-09-04-research-identity-scan-error-contract.md`** — the
investigation, filed as supplied. No finding, recommendation, table row or evidence line
was edited. One mechanical correction only: the text arrived with UTF-8 mojibake (`â`
where an em dash belongs, `â¦` where an ellipsis belongs) and those characters were
restored to `—` and `…`. Nothing else changed.

**2. `BACKLOG.md` BL-033 — description replaced, ID kept.** The thin pre-investigation
note became the investigation's actual findings: that the eight exceptions are *identical*
over the wire rather than merely hard to distinguish (no `server.error.include-message`
anywhere in `backend/`, no `@ControllerAdvice` in `backend/src`, so `DefaultErrorAttributes`
discards the reason); the required first step of one `curl` against a running instance
before any code, because the central evidence is a ~week-old capture on a Boot version past
the researcher's knowledge cutoff; the fix shape (per-exception `@ExceptionHandler`, `409` +
a `code` field via `ProblemDetail`, following `AccountCheckController.java:74-81`, status
held at 409 so the existing mobile mapper survives); and the three cases needing more than a
code — `blockedUntil` on the 24-hour block, other-document-type budget on type-exhausted,
and the `REGISTRY_NOT_READY` split out of `NoActiveRegistryReviewException`. Marked do-before-S5-07,
deferred by product-owner decision to a pre-S5-07 slice, not yet scheduled. Cross-references BL-034.

**3. `BACKLOG.md` BL-034 — new.** The dropped-acknowledgement defect, split out of
exception #8 because it is a live behaviour bug rather than a presentation gap and is worth
fixing whether or not BL-033 proceeds. `insertAcceptedCycle` always opens a new cycle over a
plain INSERT, so an identical re-upload always collides with `scan_result.uqudo_jti` UNIQUE,
and nothing reads the active cycle's jti to tell a retry from a replay. Fix is a transplant
of the S3-13 `currentFaceResultJti` pattern. Sequenced **before** BL-033.

**4. `RISKS.md` R-052 — new, ACTIVE register (line 57), status 🟡 Watching.** S5-07 starting
before BL-033/BL-034 land, whose likely workaround is the app inferring scan state and
attempt counts locally — the thing the server-side budget exists to prevent — spread across
five screens before anyone notices. Resolution path: BL-034 then BL-033 as a backend slice
before S5-07 begins.

Placement was checked explicitly, because putting a risk in the wrong table was one of this
session's two earlier blockers: R-052 sits at `RISKS.md:57`, inside the active table (which
ends at line 58), above both the "Retired at creation" list and the
"Delivery notes — outside the deliverable" section and its separate table at line 65.

**5. `EXECUTION_PLAN.md:83` (S5-07) — blocker note extended only.** No row was reordered, no
status changed, no other row touched. The existing "the third is still open … BL-033" sentence
now also names BL-034 and the investigation report, and states the ordering (BL-034 first,
then BL-033, before these screens are built), pointing at R-052.

---

## Not done, deliberately

- **The 200-vs-409 question is not settled**, matching the investigation's own refusal to
  settle it. Whether `/scan-result` should report non-acceptance as a 200 outcome instead
  belongs with whoever scopes S5-07, with the screens on the table.
- **Part 8's two passing observations** (the `/registry-review/*` terminality gap; the
  `issueToken` defensive branch at `:180`/`:239` that cannot enforce what its comment claims)
  were **not** filed as backlog rows. They are outside the three items this task specified,
  and Part 8 marks them as unresolved rather than concluded. They remain readable in the
  investigation report; filing them is a separate call.
- **No component card written.** Part 9's proposed two lines for `docs/components/uqudo-sdk.md`
  were not added — also outside the three specified items.
- **PROJECT_PLAN.md untouched.** Read at session start as required; nothing in this filing
  settles or reopens an architecture decision.
