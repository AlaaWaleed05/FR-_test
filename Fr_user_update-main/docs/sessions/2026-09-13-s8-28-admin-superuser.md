# S8-28 — admin becomes a superuser (AD-013's second half, BL-139)

**Date:** 2026-09-13
**Task:** EXECUTION_PLAN.md S8-28 · closes BL-139 · files BL-140, BL-141
**Branch:** `main` (five commits, each gated, reviewed and pushed before the next began)

AD-013 has two halves. S8-27 built the four-eyes removal. This session built the role
ladder: viewer views; operator views, prints, approves, rejects, manually completes; admin
does everything an operator may, **plus** sole authority to create and manage back-office
users. AD-002e's own row is left unedited, as the readable record of what was true until
2026-09-13.

---

## The brief was wrong about the mechanism, and it mattered

The brief, BL-139 and AD-013 all name `OperatorIdentityFilter`'s `role() != ADMIN` predicate
as "the single change without which this does not work, whatever `SecurityConfiguration`
says". Verified against source in plan mode, before any code:

**It is necessary and not sufficient.** An admin was refused by Spring Security's
`AuthorizationFilter`, because `OperatorUserDetails` granted an admin `ROLE_ADMIN` alone and
`SecurityConfiguration:149-150` gates the operator prefix on `hasRole("VIEWER")`. That filter
runs *after* `OperatorIdentityFilter`, so `OperatorIdentityArgumentResolver` — which the brief
blamed — never ran at all for an admin. Removing only the predicate leaves the 403 exactly
where it was. Two gates had to move.

The brief also missed that **approve, reject, manual-complete and export each carry their own
`hasRole("OPERATOR")` rule** ahead of the coarse one. Those are precisely AD-013's named admin
powers; fixing the prefix gate alone would have left every one of them 403.

Other corrections found at source:

| Brief said | Actually |
|---|---|
| filter is at l.53 | l.54 (BL-139 right, AD-013 stale) |
| catch-all is `hasRole("VIEWER")` | catch-all is `denyAll()`; VIEWER is the operator-prefix rule |
| `RequireRole` is a file | exported from `RequireAuth.tsx:45` |
| one test pins the old behaviour | **three** |
| AD-013 cites operator.md l.35-38 | l.46-52 after S8-27's edits |

Two hazards nobody had named: `AppShell.tsx`'s `ROLE_LABEL_AR` had no admin entry and falls
back to `?? state.role`, so the first admin to reach the shell would have seen the ASCII word
`admin` in an Arabic-first RTL header; and the resolver's 401 message literally read "signed
in as admin".

Confirmed as the brief stated: `OperatorAccessLevel` two-valued, `is_back_office` false for
admin, `OperatorRole`'s javadoc pointing at BL-139.

---

## The decision the brief asked me to check — and it was right to

**AD-013 does not rule on `is_back_office`.** Its row names V0057 only inside a pointer to
wayfinder ticket 06; the ticket alone says the flag "becomes true for admin". Separately, the
column had **zero consumers** in any tier — no Java, no SQL, no TypeScript, no Dart, no test,
and `JdbcOperatorUserRepository` never joins `app.operator_role`. Flipping it would have
changed nothing behavioural.

Raised rather than settled, per CLAUDE.md. **Product-owner ruling taken this session: drop the
column** (V0068). A boolean identical on all three rows is not data, and the frozen comment on
it — "false for 'admin', which manages accounts and reaches no operator endpoint" — had already
gone stale silently, which is this project's fifth such finding.

---

## Design decisions

**`OperatorAccessLevel` stays two-valued.** Admin maps *onto* `OPERATOR` rather than becoming a
third level: "everything an operator may" is exactly what that level already means. A third
constant would force every `!= OPERATOR` gate in the service tier to be rewritten as set
membership for no gain. Admin's extra authority is the `/api/v1/admin/**` surface — a Spring
Security rule keyed on `ROLE_ADMIN`, not an access level.

**No `SecurityConfiguration` rule changed** — only its comment. The hierarchy lives in the
authority mapping (`ADMIN → ROLE_ADMIN + ROLE_OPERATOR + ROLE_VIEWER`), extending the idiom
`OPERATOR` already used. One place to read, one place to get wrong, and no authorization rule
mentions admin at all.

**`accessLevelOf` is an exhaustive switch**, so a fourth role becomes a compile error rather
than a silent `VIEWER` fallback — strictly better than the ternary it replaced.

---

## The audit gap this opened, and closed

Because admin and operator now arrive at the same access level, their actions were about to
become **byte-identical in the append-only chain**: both write `actor_kind='operator'` plus an
`actor_id` UUID. R-054 records that separation of duties no longer exists in the back office
and that the audit trail is the **sole** compensating control. Joining back to
`app.operator_user.role` is not an answer — that column is mutable, so it testifies about now,
not about the moment of the action.

`operator.domain.OperatorAuditPayload` now stamps `actorRole` at five sites (approve, reject,
approve-refused, manual completion, export, profile view, list search). This is AD-002e's own
recorded design in `persistence.md` being implemented, not a new decision. No CHECK altered, so
R-035 is not engaged; no migration, since only new events carry the field and no existing row
is re-hashed; `CanonicalJson` sorts keys, so insertion order is invisible in the hashed text.

**One site deliberately excluded: `profile_image_viewed`.** Wayfinder ticket 09 decision 1 fixes
that payload at "kind + artifactId and nothing else" and an integration test asserts the field
count. That is a product-owner ruling on that specific payload, so this session left it rather
than overriding it in passing. **Filed as BL-140** for a ruling. `AuthAuditRecorder`'s sign-in
events are also excluded: they sit on the actor's own chain with their own UUID as both subject
and actor, so there is no second party and no business action to separate.

### R-054 — updated, NOT retired

Per CLAUDE.md, R-054 stays live until a replacement control is decided. Two changes recorded on
the row rather than left to be noticed:

- **Worse:** until today an admin wanting to complete-then-approve needed a *second account*,
  whose creation was itself an event in the trail. One account now does the whole chain, so the
  artefact that would have betrayed it is gone.
- **Better, partially offsetting:** the trail can finally answer "did the account-creator
  approve this?" without a mutable join. That answer did not exist before today.

The row's "BUILT at S8-27" claim also only ever covered the four-eyes half; it now covers both.

---

## Gates

Every commit ran the full gate for each tier it touched before being pushed.

**Backend — `./mvnw verify -Pdb-integration-test`** (the real gate; plain `verify` measures
~60% and excludes the integration suite that covers exactly this code):

```
[INFO] Tests run: 1140, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  02:10 min
```

**Back office** (isolated Node 24.19.0):

```
lint_exit=0

 Test Files  21 passed (21)
      Tests  172 passed (172)
   Duration  95.71s (transform 1.33s, setup 11.78s, import 80.41s, tests 122.64s, environment 52.73s)
All files          |   97.23 |    86.77 |   97.64 |   98.96 |

dist/index.html                     0.53 kB │ gzip:   0.37 kB
dist/assets/index-MI8JscGq.css      0.30 kB │ gzip:   0.20 kB
dist/assets/index-CZHsxyE4.js   1,256.66 kB │ gzip: 397.25 kB
✓ built in 1.31s
```

Mobile untouched, so its gates were not run.

---

## Live proof

**Why live proof and not only a test:** the authorization chain is assembled from Spring
Security configuration, a servlet filter and an authority mapping, none of which MockMvc's
`addFilters = false` integration tests exercise together. A green suite could coexist with an
admin still 403'd in a real browser session.

Real backend jar against the compose Postgres (migrated to V0068 as `fru_migrator` first — the
shipped jar connects as `fru_app` and holds no DDL rights), a real admin account created
through `CreateOperatorAccountRunner`, a real form-login session with CSRF:

```
POST /auth/login (admin)                       200
POST /auth/password (forced change)            204
POST /auth/login (admin, new password)         200
  /auth/me says: {"username":"s828.admin","displayName":"Admin Superuser","role":"admin","mustChangePassword":false}
  profile created by the customer journey: fcc0f522-5804-419b-b023-81b5331a3a6f
GET  /operator/profiles            (list)      200
GET  /operator/profiles/{id}       (view)      200
GET  /operator/profiles/export     (export)    200
POST /operator/profiles/{id}/manual-complete   200
POST /operator/profiles/{id}/reject            200
POST /operator/profiles/{id}/approve           200
```

Every one of these returned **403** before this session. And the trail, read out of the live
append-only table:

```
         event_type         | actor_kind | actor_role
----------------------------+------------+------------
 sign_in_succeeded          | operator   |
 sign_in_succeeded          | operator   |
 password_changed           | operator   |
 sign_in_succeeded          | operator   |
 profile_list_searched      | operator   | admin
 profile_exported           | operator   | admin
 profile_viewed             | operator   | admin
 profile_manually_completed | operator   | admin
 profile_rejected           | operator   | admin
 profile_approved           | operator   | admin
```

The blank `actor_role` on the three auth events is the documented `AuthAuditRecorder`
exclusion, visible rather than asserted. Final profile status: `approved`.

**V0068 applied to an already-migrated database** (a passing test would not show this — the
integration suite migrates from empty, where dropping an unused column trivially succeeds):

```
 version |            description                | success
---------+---------------------------------------+--------
 0068    | app operator role drop is back office | t

Table "app.operator_role" -> code, label_ar, label_en      (is_back_office gone)
Referenced by: operator_user_role_fkey FOREIGN KEY (role) -> code   [intact]
admin | مدير النظام | Administrator
operator | مشغّل | Operator
viewer | مطّلع | Viewer                                    [3 rows intact]
```

**No revert-restore is owed.** Both new assertions are direct wrong-value assertions, which
per CLAUDE.md cannot pass against the bug: `payload->>'actorRole'` returns SQL NULL when the
key is absent, and `assertEquals("operator", null)` fails; the admin-button test throws on
`getByRole` because `role === 'operator'` is false for an admin.

**Credential handling:** the runner's one-time password was written to a scratchpad file
outside the repo, read from there by the walk script, never echoed into the transcript, and
deleted afterwards along with the cookie jar.

---

## Review findings and dispositions

`@agent-reviewer` was run against the brief's claims **before** coding — which is what caught
the mechanism error above — and against each commit's diff before it was pushed.

| # | Finding | Disposition |
|---|---|---|
| 1 | New integration test left claimable outbox rows after two successful approves; `NotificationOutboxIntegrationTest`'s unscoped `CLAIM_ONE_PENDING` orders by `next_attempt_at` and would claim the stray first | **Fixed.** Real order-dependent cross-class flake that passed this run and would not always. Added the two `keepOutboxRowsUnclaimable` calls the class's own javadoc requires |
| 2 | `AdminHomePage`'s new Arabic told the admin they could **print**; no print feature exists in `backoffice/src` (BL-132, unbuilt) | **Fixed.** The rewrite had moved the untrue claim from one clause to another rather than removing it — the precise defect it was made to fix. Dropped «وطباعتها» |
| 3 | `RequireAuth.tsx`'s javadoc still quoted operator.md's "the UI must not present operator screens to an admin", in a file commit 4 would not otherwise have touched | **Fixed.** Exactly the self-confirming frozen comment CLAUDE.md names |
| 4 | The first `RequireAuth` test added passed an explicit roles array, so it exercised unchanged code and would pass against the pre-BL-139 build — while `App.tsx`'s actual change had no test at all | **Fixed.** Route list extracted to `PROFILE_SCREEN_ROLES` and asserted directly |
| 5 | `ManualCompletionService`'s comment calls it the most load-bearing site, but only `profile_approved` was asserted end to end | **Fixed.** `ManualCompletionIntegrationTest` now reads `actorRole` from the real payload |
| 6 | `AppShell`'s javadoc claimed the operator-extra actions "don't exist as screens yet" (true at S6-01) | **Fixed** while the file was open |
| 7 | `ProfileDetailPage`'s admin test asserted two of three gates; manual-complete needs an eligible status the default fixture lacks | **Fixed.** Second case added |
| 8 | Comment said "the four specific rules"; approve/reject/manual-complete/export are four endpoints across **two** rules | **Fixed.** Wording corrected in both places |
| 9 | `git add -A` would have swept the untracked `docs/bank-hosting-specification.md` into an authorization commit | **Heeded.** Every commit staged explicitly by path; that file remains untracked and untouched |
| 10 | `PROJECT_PLAN.md:417-418` said AD-013 makes admin "a third, higher level", which the chosen design contradicts | **Fixed in commit 5.** Reworded to distinguish the three-deep ROLE ladder from the two-valued ACCESS LEVEL |

Reviewer verdicts on the security questions, verified rule by rule: admin gains exactly the
four operator endpoints and the operator prefix and nothing else; no role gained `ROLE_ADMIN`;
the `must_change_password` path still returns before the switch, so a must-change admin is
still granted only `ROLE_PASSWORD_CHANGE_REQUIRED`; the disabled-account live-session behaviour
is unaffected, since the removed predicate ran only inside an already-present `Optional`;
`actorRole` is never read for an authorization decision anywhere in the repo.

---

## Documents corrected

The live prose, separate from the AD table and the thing S8-27 missed:

- `PROJECT_PLAN.md` — the system-map bullet (three-role hierarchy; and the ROLE-ladder /
  ACCESS-LEVEL distinction stated explicitly, since they differ in depth)
- `docs/journeys/operator.md` — the access-model table gains an Admin row; the "not a
  back-office access level" paragraph, which is what AD-002e's row points at rather than
  restating, is marked superseded and rewritten
- `docs/components/backoffice-auth.md` — the authority mapping, the identity-filter behaviour,
  and its open item on operator.md, now closed
- `docs/components/persistence.md` — the `actorRole` item moves from OBSERVED to implemented,
  naming the excluded site
- `docs/road-to-production.md` — "deliberately excluded from operator screens" corrected
- `RISKS.md` — R-054 (above) and R-018's mitigation column

Not edited, deliberately: AD-002e's row, everything under `docs/sessions/`, and
`.scratch/backoffice-remaining/`.

---

## Scope held

Ticket 07's admin user-management UI (BL-023) was **not** built — BL-139 was scoped in plan
mode to the authorization layer plus the back-office gates that would otherwise make it
invisible. AD-013's "viewer gets no print" was not built either: no print feature exists in
`backoffice/src` at all.
