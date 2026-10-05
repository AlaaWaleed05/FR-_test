# 2026-09-04 — Plan-file reconciliation: MVP scope cut

Docs-only. No code changes, no test/analyze gates apply.

## 1. S6-03 cancelled

EXECUTION_PLAN.md, Sprint 6:

> | S6-03 | Export, and admin account management | ❌ | Scope cut from Phase 1 MVP by
> product-owner decision, 2026-09-04. Admin HTTP screen already tracked as BL-023.
> Export tracked as new BL-026 (added below). |

## 2. Export filed as BL-026; note added to BL-023

BACKLOG.md gained:

> | BL-026 | Operator-facing export (XLSX/CSV) of profile field data, per operator.md's
> Export section. **Correction to the task text that filed this row**: the backend
> endpoint is not missing — `GET /api/v1/operator/profiles/export`
> (`operator.web.ExportController`/`ProfileExportService`/`ProfileExportWriter`) was
> already built and proven at S4-02. What's actually missing, and what S6-03 would have
> built, is the back-office UI: no export button/page exists in `backoffice/` to call
> it. | Cut from Phase 1 MVP scope by product-owner decision to keep the sprint lean —
> the core approve/reject/manual-complete loop does not depend on it. Revisit after
> Phase 1's core journey (mobile stages 7-12) is demonstrable end to end. | 2026-09-04 |

**Deviation from the task text, and why**: the task's given BL-026 wording said "No
endpoint or UI exists." Verified against the actual codebase before writing anything —
`operator/web/ExportController.java`, `operator/service/ProfileExportService.java`,
`operator/domain/{ProfileExportWriter,ExportRow,ExportOutcome}.java` all exist, and
EXECUTION_PLAN.md's own S4-02 row (✅ Done) already documents `GET
/api/v1/operator/profiles/export` built, reviewed twice, and gate-proven (508/508 at the
time). Writing "no endpoint exists" into BACKLOG.md would have been a false claim in a
ground-truth file. Corrected the wording to say what is actually true: S6-03's real gap
was always the *back-office UI* half (a page/button to call the endpoint), not the
endpoint itself. This does not change what's cut from Phase 1 or why — only the accuracy
of what's being deferred.

BL-023 gained one line:

> **Also cut from Phase 1 MVP scope by product-owner decision, 2026-09-04** — same
> decision that cancelled S6-03; still deferred, not built here.

## 3. New mobile task — S5-08 (stages 10-12)

Added to EXECUTION_PLAN.md, Sprint 5, immediately after S5-07, exactly as specified:

> | S5-08 | Stages 10-12 mobile screens — liveness/face match (Uqudo SDK), signature
> capture, completion and submission | ⬜ | Follows S5-07. No task in this plan
> currently covers these stages on mobile, even though the backend has supported them
> since S3-13 (docs/sessions/2026-08-31-s3-13-liveness-signature-completion.md). Found
> by the 2026-09-03 MVP scope audit. Backend endpoints already exist and are proven;
> this is mobile-only. |

Confirmed the gap is real before adding it: grepped EXECUTION_PLAN.md for any existing
task covering mobile stages 10-12 — S5-02 through S5-07 cover stages 0/1a/1b, 2, 3-6, and
7-9 respectively; nothing names 10-12. S3-13 (backend) is ✅ Done and covers liveness,
signature and submission server-side.

## 4. New task — S6-04 (outbox dispatcher scheduler)

Added to EXECUTION_PLAN.md, Sprint 6, after S6-03's now-cancelled row, exactly as
specified:

> | S6-04 | A scheduled runner that drains app.notification_outbox | ⬜ | Every
> DEFERRED-urgency message (submission notification, every status-transition
> notification) is enqueued and never sent — no scheduler exists anywhere in this
> codebase to call OutboxDispatcher.dispatchOnePending(). Disclosed in
> OutboxDispatcher.java:37-39 and ContactChannelsService.java:103 but never filed. Found
> by the 2026-09-03 MVP scope audit (section 3.2). Needed for Phase 1's own
> "demonstrable end to end" bar — without it, even a fully-stubbed demo cannot show a
> customer receiving a message. |

## 5. PROJECT_PLAN.md reconciliation

Checked the module map and the S6-03/BL-023/BL-026 touch points:

- **Module map** (`backoffice/` bullet) still reads "...checks per-profile status and
  history, and exports field data." Left unchanged — this describes the back office's
  full intended scope, and this project's own precedent (BL-001, the statistics
  dashboard, deferred without editing the Overview's "read aggregate statistics on a
  dashboard" line) is that the Overview/module map name the whole product's intended
  capabilities; BACKLOG.md is where phase/sprint deferral status lives. No correction
  needed by that precedent.
- **Delivery model section** (added 2026-09-03): this scope cut is exactly the kind of
  dated, decisions-log-style entry that section exists for, so a new paragraph was
  added:

  > **2026-09-04 — Phase 1 scope cut: export UI and admin account management.**
  > Product-owner decision to keep the sprint lean: the back-office export UI and the
  > admin HTTP screen (listing/disabling/role-changing operator accounts) are both cut
  > from Phase 1's back office. Neither gates the core approve/reject/manual-complete
  > loop. S6-03 (EXECUTION_PLAN.md) cancelled; tracked as BL-026 (export) and an added
  > note on BL-023 (admin screen) in BACKLOG.md. The backend export endpoint itself is
  > unaffected — it already exists (S4-02) and is not part of the cut.

  This is a judgment call beyond the task's literal four items, made because leaving a
  Phase-1-scoping decision undated and unrecorded in the one section built for exactly
  that purpose would be inconsistent with why that section was added the day before.
- No other PROJECT_PLAN.md text names S6-03, BL-023, or export/admin-screen scope, so no
  further changes were made.

## Files changed

- `EXECUTION_PLAN.md` — S6-03 cancelled; S5-08 and S6-04 added.
- `BACKLOG.md` — BL-026 added; BL-023 gained one line.
- `PROJECT_PLAN.md` — one dated paragraph added to the Delivery model section.

## Commit proof

```
$ git log --oneline -1
f78175e docs: reconcile plan files for Phase 1 MVP scope cut

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `fb61fa7..f78175e  main -> main`.
