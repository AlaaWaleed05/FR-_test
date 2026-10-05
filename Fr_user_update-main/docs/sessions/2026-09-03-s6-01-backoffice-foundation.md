# S6-01 — Back office foundation: shell, sign-in, forced password change, profile list

## 0. Commit check

`git log --oneline -3` at session start: `e91f204` (S4-06 proof append) at HEAD. `git status`
clean. `git merge-base --is-ancestor 707dcf4 main` exit 0 — S4-06's stated commit is an ancestor
of `main`.

## 1. What `backoffice/` contained before this session

Exactly the Vite scaffold the task described, confirmed by reading every file rather than
assuming: `App.tsx`/`main.tsx` rendered a static `<h1>`, one passing test (`App.test.tsx`),
`ConfigProvider direction="rtl" locale={ar_EG}` already wired in `main.tsx`, antd 6.6.1 + React
19.2.8 + Vite 8 installed, Vitest v8 coverage thresholds already configured at 80% in
`vite.config.ts`. No `api/`, `auth/`, `layout/` or `profiles/` directories, no router, no HTTP
client, no auth state. `oxlint` was already the configured linter (not ESLint).

## 2. Design decisions

Full reasoning is in the EXECUTION_PLAN.md S6-01 row and in-code comments; not restated here.
Summary: `react-router-dom` 7.18.3 is the only new dependency (MIT, checked against AD-006);
native `fetch` and a bespoke `AuthContext` cover data-fetching and auth state — no query-cache or
state-management library earned its place for one list endpoint plus two small reference lists.
CSRF handling (`api/http.ts`) was verified empirically against the real running backend with
`curl` before writing any frontend code, not assumed from `CsrfConfigurer::spa`'s documentation:
a plain `GET` through chain 1 sets a non-HttpOnly `XSRF-TOKEN` cookie regardless of outcome (even
a 401), and every unsafe verb must echo it back as `X-XSRF-TOKEN` or gets a bare 403 distinct from
a 401 auth failure.

## 3. Reviewer findings

`@agent-reviewer` ran twice per CLAUDE.md's rule; the second pass found two real defects in the
first pass's own fixes (this project's pattern continues), and this session's own live Playwright
verification — run *after* both reviewer passes — found a third, genuinely live-only defect no
unit test could have caught. Full disposition table (findings, fixes, verdicts) is in the
EXECUTION_PLAN.md S6-01 row; not duplicated here. In one line: reference-list fetch failures now
retry and surface a warning; a non-401/403 `/me` failure now resolves to an explicit error screen
with retry instead of a permanent spinner; the debounced search box now resets pagination
correctly (and only on a real change, not on every mount); a wrong-current-password 401 no longer
bounces the operator to `/login`; a failed sign-out still clears local state and navigates; and
`logout()` now re-primes the CSRF cookie the backend expires on its own response, which is what
the section below proves.

## 4. Proofs

### 4a. Live backend + Postgres, all three roles

Backend jar run locally (`fru.core-banking.client=stub`, `fru.messaging.*.provider=stub`,
`fru.uqudo.client=stub`, `fru.civil-registry.client=stub`, `.env`-sourced DB creds), schema
migrated to V0059 first (`./mvnw flyway:migrate`, was at V0058). Four synthetic operator accounts
inserted directly via SQL against `app.operator_user` (`s601.viewer`, `s601.operator`,
`s601.admin`, `s601.newuser`) — **not** via `CreateOperatorAccountRunner`: that CLI runner has two
overloaded 3-argument constructors with neither `@Autowired` nor a no-arg constructor, which
Spring's constructor resolution cannot disambiguate; a real `java -jar ... --spring.profiles.active=
create-operator-account` invocation fails at context refresh with `NoSuchMethodException:
CreateOperatorAccountRunner.<init>()`. This is a genuine, previously undiscovered backend defect —
the class was evidently only ever exercised by direct test instantiation, never through Spring —
filed as **BL-024**, not fixed here (out of scope: no backend changes this session). Password
hashes generated locally with `bcryptjs` (`{bcrypt}$2b$10$...`, verified compatible with Spring's
`BCryptPasswordEncoder`) and inserted via `docker exec psql` reading from stdin (no password in
argv, matching the project's own S5-04 fix precedent).

Live `curl` proof, cookie jar per session:
- `s601.viewer` signs in → `GET /me` 200 `{"role":"viewer",...}` → `GET /operator/profiles` 200.
- `s601.admin` signs in → `GET /me` 200 `{"role":"admin",...}` → **`GET /operator/profiles` 403**
  (`hasRole("VIEWER")`, which admin never holds) — the server-side half of "an admin never
  reaches operator screens," independent of anything the UI does.
- `s601.newuser` (freshly inserted, `must_change_password=true`) signs in → login response
  `{"mustChangePassword":true,...}` → `GET /me` **403** (excluded by `hasAnyRole("VIEWER","ADMIN")`
  since it holds only `ROLE_PASSWORD_CHANGE_REQUIRED`) — confirms the `/me` 401/403 signal this
  session's `AuthContext` boot logic depends on.
- CSRF: `GET /me` unauthenticated still sets `Set-Cookie: XSRF-TOKEN=...`; `POST /auth/login`
  without `X-XSRF-TOKEN` → 403; with it → 401 (wrong password) or 200.

### 4b. Real app in a real browser (Playwright/Chromium, headless, no project skill existed for
this — none of the run-skill fallback patterns needed adaptation beyond the standard dev-server +
drive pattern)

Eight `app.profile` rows visible in the list: the one pre-existing real row from an earlier
session's live proof (`0000000001`/branch 16/`in_progress`) plus seven synthetic fixtures inserted
through the same legal path the application code uses (`audit.ensure_profile_chain` +
`audit.chain_append` trigger + the V0020 status-transition/history guards — each status hop its
own transaction, since the deferred history-check binds to the single most-recent history row per
profile). Spread across `in_progress` (×3), `submitted` (×2, one digital one manual), `approved`,
`rejected` (REJ-01), `abandoned`.

- Sign in as viewer → lands on `/profiles`, table renders all 8 rows, header shows display name +
  role, `تسجيل الخروج` link present.
- Free-text search `الخرطوم` → narrows to exactly the 3 rows on branches الخرطوم/الخرطوم بحري
  (substring match against the branch label, `ref.ar_fold`-folded server-side on both sides).
- Search `المعموره` (ه) against the stored `المعمورة` (ة) → matches — live proof of the
  ta-marbuta/ha fold, not asserted from the SQL alone.
- Status filter → `submitted` narrows to 2 rows; provenance, branch and rejection-reason filters
  each independently proven via the browser network log showing the query string change
  (`status=`, `provenance=`, `branchCode=`, `rejectionReasonCode=`) and the response `total`
  changing with it — nothing computed client-side.
- Sort by account number (header click) and pagination (page 2 of a synthetic 25-row mock in the
  Vitest suite; live-side the real 8-row set fits one page) both re-query the server with
  `sortField`/`sortOrder`/`page` — server-side sort/paging confirmed the same way.
- `s601.newuser` signs in → lands on `/change-password` only, no nav, no sidebar, only a
  `تسجيل الخروج` escape hatch — screenshot confirms no path to any other screen.
- `s601.admin` signs in → lands on `/admin`, a page naming that admin accounts don't reach
  back-office screens, no `الملفات` text anywhere in the DOM — confirmed both visually and via
  `document.body` text search.
- `DatePicker.RangePicker` under RTL (`docs/components/backoffice-components.md`'s own "do
  first" open item): opened and screenshotted. Two-panel calendar, genuinely Arabic weekday
  names, no clipping. Panel order is earlier month on the right (closer to "start"), RTL-
  consistent — not the v5 LTR-mirrored bug the card's own note already closed. Month/year headers
  render in English (`dayjs` default), consistent with the table's own date-column formatting.
  Marked verified on the card.
- `document.documentElement` carries `dir="rtl" lang="ar"` after the `index.html` fix.
- The CSRF/logout defect (§3): live-proven both broken (before the fix, `POST /auth/login`
  after a sign-out returned 403) and fixed (after: sign out → sign in as a different account →
  200; and a full real password-change → sign out → sign in with the *new* password → 200).

### 4c. Session-expiry mid-use

The backend's 30-minute idle timeout is already proven live at S4-05
(`docs/components/backoffice-auth.md`); waiting it out is not repeated here. What is new this
session is the *frontend's* reaction to a 401 discovered mid-session, which is inherently a
client-side behaviour — proven directly, not indirectly, by
`AuthContext.test.tsx`'s "a 401 discovered by ANY later call drops the session back to anonymous"
test: mocks a 401 from an unrelated in-flight call, asserts `state` drops to `anonymous`, and
(via `RequireRole`'s own test) that the resulting redirect preserves `state.from` for a
return-to-the-same-place re-login. A green test here cannot coexist with a broken redirect, so
per CLAUDE.md's live-proof discipline this is the direct assertion, not a stand-in for one that
needed to be live.

### 4d. Digital vs manual

`9000000005` (branch سنار, submitted, `provenance=manual`, purple "يدوي" tag) sits beside seven
`provenance=digital` ("رقمي", blue) rows in the same table — visually and structurally distinct,
per operator.md's requirement that they never be conflated.

## 5. Gates

Backoffice only; backend and mobile untouched this session (`git status --porcelain | grep
'^\s*[AM?]\+\s+(backend|mobile)/'` — no match).

`npx tsc -b --noEmit`: clean.

`npm run lint`:
```
oxlint — exit 0. 9 warnings (react/set-state-in-effect on data-fetching effects — a known
false-positive category for that rule; react/only-export-components on AuthContext.tsx exporting
both a provider and a hook, a deliberate, standard pattern; react/globals on 5 test-only
`contextValue` capture variables). No errors.
```

`npm run test:coverage` (final run, after all reviewer and live-verification fixes; run 5
consecutive times with identical results, closing an intermittent flake the second reviewer pass
caught in 1 of 5 runs before the mount-time debounce bug was fixed):
```
Test Files  13 passed (13)
     Tests  93 passed (93)

 % Coverage report from v8
-------------------|---------|----------|---------|---------|
All files          |   98.82 |    90.17 |     100 |     100 |
-------------------|---------|----------|---------|---------|
Statements   : 98.82% ( 335/339 )
Branches     : 90.17% ( 156/173 )
Functions    : 100% ( 87/87 )
Lines        : 100% ( 304/304 )
```
All four well above the 80% gate.

## 6. Package check (AD-006 standard)

- `react-router-dom` 7.18.3 — MIT, published within the week of this session (`npm view`) — passes.
- `@testing-library/user-event` 14.6.7 (devDependency, test-only) — MIT, published within a day —
  passes.

## 7. Left open / disclosed, not fixed

- **BL-024 (new)**: `auth.config.CreateOperatorAccountRunner`'s ambiguous-constructor Spring
  wiring bug (above) — a backend defect, out of scope for a backoffice-only session.
- The branch/rejection-reason filter `Select`s do unfolded Arabic substring search in their own
  dropdown (antd's `showSearch`, not `Table`'s `filterSearch`) — disclosed on
  `docs/components/backoffice-components.md` and `BACKLOG.md` BL-014, not built speculatively.
- `docs/components/backoffice-components.md`'s `Timeline`/`Image.PreviewGroup` RTL open item is
  still open — neither component exists yet (S6-02).
- Single profile view, approve/reject, manual completion (S6-02); export, admin account
  management (S6-03); the dashboard (BL-001); document images (R-046, AD-002d) — all explicitly
  out of scope per the task, untouched.

## 8. Commit and push

```
$ git log --oneline -1
8f47370 feat: S6-01 -- back office foundation: shell, auth, profile list

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed straight to `main` (`git push`: `e91f204..8f47370  main -> main`), per CLAUDE.md's
session-end rule — no feature branch.
