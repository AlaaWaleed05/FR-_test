# 2026-08-21 — S1-09: File the journey specification and reconcile the plan files

Documentation and reconciliation session. No feature code, no scaffold changes, no Uqudo
dependency, no new dependencies.

## Step 0 — commit check

`git log --oneline -3` at session start:

```
d63b4c9 docs: record S1-05 commit/push proof in session report
687d55b chore: complete CLAUDE.md, add and prove generated-file guard hook (S1-05)
526acd6 docs: record S1-07 commit/push proof in session report
```

S1-05 confirmed at `687d55b`, matching the expectation in the session prompt.

`git status` at session start showed three untracked trees:

```
Untracked files:
	docs/journeys/
	docs/list Docs/
	docs/reference/
```

`docs/journeys/` and `docs/reference/` were anticipated by the session prompt — the product
owner's input for this session (`customer.md`, `operator.md`, `journey-open-items.md`,
`branches.md`, `NSudan_Admin_Hierarchy.xlsx`, `كود المهنة.xlsx`).

`docs/list Docs/` was **not** anticipated by the prompt. It contains: `إدارة الإلتزام.pdf`
(the bank's paper form, also named as supplied reference material inside
`journey-open-items.md`'s own "Reference data supplied" section), two WhatsApp photos of an
administrative-division reference table, and two `.xlsx` files (`Sudan_Admin_Hierarchy.xlsx`,
`Sudan_States_and_Localities.xlsx`) that read as earlier drafts of the cleaned dataset now
in `docs/reference/NSudan_Admin_Hierarchy.xlsx`. Both photos were opened and visually
confirmed to be a data table, not identity documents or PII, before any git action was taken
on this directory — consistent with the hard rule against identity-document images in the
repo. The user was asked how to handle this out-of-scope directory and chose to commit it
as supporting raw material alongside the rest.

## Step 1 — source documents

Read only, not modified: `docs/journeys/customer.md`, `docs/journeys/operator.md`,
`docs/journeys/journey-open-items.md`, `docs/reference/branches.md`. Confirmed present:
`docs/reference/NSudan_Admin_Hierarchy.xlsx` and `docs/reference/كود المهنة.xlsx`.

## Plan files edited

### EXECUTION_PLAN.md
Added the S1-09 row (🔵, set to ✅ only if step 8's verification passed — see below).

### PROJECT_PLAN.md
- **Overview** — added the journey-specification paragraph pointing at
  `docs/journeys/customer.md` and `docs/journeys/operator.md`, noting they supersede the
  original ten-screen Arabic concept, plus a pointer to `docs/reference/`.
- **Constraints** — added three: core banking is read-only (no write path from this system),
  the app must collect the same data as the bank's paper form segmented per
  `customer.md`, and no reference list is hardcoded.
- **Architecture** — added five facts: the two-layer persistence model with per-field
  ownership and idempotency keys, the audit trail as a distinct hash-chained component, the
  profile status model, permanent non-merged provenance, and the "verified claim, operator
  decides" design principle.
- **Open questions** — added OQ-012 (corrected admin-divisions dataset), OQ-013 (operator
  auth, part of AD-002), OQ-014 (dashboard metric list).
- **Open architecture decisions** — extended AD-002's description to cover reference-data
  delivery/caching/versioning and the audit store; added AD-004 (image and artifact
  storage), verbatim per the session prompt.

### RISKS.md
Appended R-011 through R-020. R-011 (Civil Registry outage, no fallback) and R-012 (Uqudo
token 1800s expiry vs. resumed session) were added first since `customer.md` referenced them
but they were not yet in the file. R-013–R-020 added exactly as tabulated in
`journey-open-items.md`.

### BACKLOG.md
Added BL-004 — manual profile creation by an operator with optional Civil Registry
retrieval, deferred to a later version by product-owner decision; v1 supports manual
completion of an existing profile only.

### CLAUDE.md
Two changes: the session-start rule now also requires reading `docs/journeys/customer.md`
and `docs/journeys/operator.md` for any session touching customer- or operator-facing
behaviour; one new hard rule that reference lists are never hardcoded and the list version
used for a submission is recorded on the profile.

**Resulting line count: 121 lines** (under the 200-line ceiling).

## Cross-checks

**R-### referenced in `customer.md`/`operator.md` now exist in RISKS.md:** `customer.md`
references R-002, R-011, R-012, R-020 — all present. `operator.md` references no R-### IDs.
No gaps found.

**OQ numbering / AD-004 uniqueness:** OQ-001 through OQ-014 are continuous with no
duplicates. AD-004 appears exactly once in PROJECT_PLAN.md.

## Gate output — all three tiers, no regressions

### backend (`./mvnw verify`, `JAVA_HOME` set to the JBR)

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 0 were already clean, 2 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

### backoffice (`npm run test:coverage`, isolated Node v24.19.0)

```
 Test Files  1 passed (1)
      Tests  1 passed (1)

Statements   : 100% ( 1/1 )
Branches     : 100% ( 0/0 )
Functions    : 100% ( 1/1 )
Lines        : 100% ( 1/1 )
```

### backoffice (`npm run lint`)

```
> backoffice@0.0.0 lint
> oxlint
```
No issues reported.

### mobile (`fvm flutter test`)

```
00:00 +0: loading .../mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
```

### mobile (`fvm flutter analyze`)

```
Analyzing mobile...
No issues found! (ran in 23.4s)
```

### mobile (`fvm dart run tool/check_coverage.dart`)

```
00:02 +1: All tests passed!
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

Nothing changed in any of the three tiers this session; all gates pass with the same shape
of output as prior sessions.

## Items from journey-open-items.md not filed

None. Every item in "New architecture decisions," "New open questions," "New risks,"
BL-004, and the CLAUDE.md reference-list rule was filed into the corresponding plan file.
The three items under "Still open — the complete list" (admin-divisions dataset,
operator authentication, dashboard metric list) are recorded as OQ-012, OQ-013 and OQ-014
respectively, not resolved — resolving them was out of scope for this session.

## Commit / push proof

```
$ git push
To https://github.com/Osmantou/Fr_user_update
   d63b4c9..c64137b  main -> main

$ git log --oneline -1
c64137b docs: file journey specification (S1-09) and reconcile plan files

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

