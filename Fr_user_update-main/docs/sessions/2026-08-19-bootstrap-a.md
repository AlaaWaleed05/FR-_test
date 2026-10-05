# 2026-08-19 — Bootstrap Stage A + reference clone

## Step 0 — Reference repository clone

`../FIB` already existed at bootstrap time (sibling of this repo, at
`C:\Users\DELL\Documents\Osman\Waleed\FIB`). Clone was skipped per the "if already
present" rule. Verified it is the correct reference and in a clean, up-to-date state:

```
$ git remote -v
origin  https://github.com/Osmantou/FIB.git (fetch)
origin  https://github.com/Osmantou/FIB.git (push)

$ git log --oneline -5
af6809d docs: Fix Session D report — EmailAndPhoneScreen, OTP simplification, SendOTP payload
d138f51 fix(Fix-D): EmailAndPhoneScreen, simplified OtpScreen, correct SendOTP payload
1d2e12d docs: OTP flow review - email+phone collected pre-OTP, /SendOTP payload analysis
cd540d7 feat(S5-E): release APK, debug logging, E2E QA checklist — Sprint 5 complete
689b99f fix(Fix-C): remove background check, fix enrollment parser, OpenCIFData to TermsScreen

$ git status
On branch master
Your branch is up to date with 'origin/master'.
Untracked files:
  .claude/
  Onboarding source as on 29 mar 2026 from 194 backend server fib.zip
  Tests/
  backend server fib/
```

The untracked items (a 90MB source zip, a `Tests/` image folder, and an ad hoc
`backend server fib/` copy) are pre-existing local additions on top of the clone, not
part of the tracked reference history. Left untouched — this repo never writes to
`../FIB`.

`.claude/settings.local.json` created in this repo (Fr_user_update) granting read
access to `../FIB`:

```json
{
  "permissions": {
    "additionalDirectories": ["../FIB"]
  }
}
```

Not committed — matched by `.gitignore`.

### FIB tree survey (top two levels)

```
FIB/
├── .claude/
├── .git/
├── BACKLOG.md, CLAUDE.md, EXECUTION_PLAN.md, MOBILE_APP_PLAN.md, README.md
├── Onboarding source as on 29 mar 2026 from 194 backend server fib.zip  (90MB, untracked)
├── To_Osman.zip  (50MB, tracked)
├── Tests/            — Test_001.jpeg, Test_002.jpeg (sample scan images, untracked)
├── Web_Code/         — built Vite bundle only (index.html, hashed assets/*.js + .map,
│                        TC-ar.pdf, TC-en.pdf, config.ini, web.config) — no web source tree
├── backend server fib/  — Redis/, utility/, "utility - Copy"/, wwwroot/ (untracked dir)
├── docs/             — API_REFERENCE.md, ARCHITECTURE.md, research/, sessions/
└── mobile/           — Flutter app: lib/, test/, android/, ios/, assets/, pubspec.*
```

### Uqudo/eKYC file survey

Ripgrep (case-insensitive) for `uqudo|ekyc|liveness|biometric|enrollment` across the
clone; build output and `Web_Code/assets/*.js(.map)` minified bundles (100+ hash-named
matches, all noise) excluded from the list below.

**Mobile — Flutter/Dart** (`mobile/lib`, `mobile/test`):
| Path | Kind |
|---|---|
| `mobile/lib/core/uqudo/uqudo_service.dart` | SDK wrapper |
| `mobile/lib/core/uqudo/uqudo_result_parser.dart` | result/model parser |
| `mobile/test/core/uqudo/uqudo_service_test.dart` | test |
| `mobile/test/core/uqudo/uqudo_result_parser_test.dart` | test |
| `mobile/lib/features/document_scan/document_scan_screen.dart` | screen |
| `mobile/test/features/document_scan/document_scan_screen_test.dart` | test |
| `mobile/lib/features/liveness/liveness_screen.dart` | screen |
| `mobile/test/features/liveness/liveness_screen_test.dart` | test |
| `mobile/lib/features/personal_info/personal_info_email_screen.dart` | screen |
| `mobile/test/features/personal_info/personal_info_email_screen_test.dart` | test |
| `mobile/lib/shared/models/kyc_state.dart`, `kyc_state_notifier.dart` | state model |
| `mobile/lib/core/network/api_service.dart`, `app_exception.dart` | networking |
| `mobile/lib/core/router/app_router.dart` | routing |
| `mobile/lib/core/config/app_config.dart`, `mobile/assets/config/app_config.json` | config |
| `mobile/lib/core/config/l10n/*` | i18n (ar/en) |
| `mobile/pubspec.yaml`, `mobile/pubspec.lock` | dependency manifest |

**Backend — Java/Spring Boot, Maven** (`backend server fib/utility/src`):
| Path | Kind |
|---|---|
| `service/UqudoService.java`, `service/Impl/UqudoServiceImpl.java` | SDK/service wrapper |
| `controller/UqudoController.java` | API endpoint |
| `config/UqudoReaderConfig.java` | config |
| `service/ApiClientService.java`, `service/Impl/BankServiceImpl.java` | orchestration / core-banking client |
| `src/main/resources/application.yml` | config |

**Docs** (prior research/session history, precedent only, not authoritative):
`docs/research/2026-06-10-uqudo-sdk-research.md`,
`docs/research/2026-06-10-backend-api-review.md`,
`docs/research/2026-06-10-webapp-uqudo-review.md`,
`docs/API_REFERENCE.md`, `docs/ARCHITECTURE.md`,
`docs/sessions/2026-06-10-*.md` (sprint 1–5, fix sessions A–D).

Languages/build systems in FIB: Flutter/Dart (`mobile/`, Flutter 3.38.7, package
`fib_kyc_mobile`, Maven not applicable); Java/Spring Boot with Maven (`backend server
fib/utility/pom.xml`); `Web_Code/` is a **built output only** (Vite-hashed JS bundles),
no web front-end source present in this clone.

All of the above recorded in PROJECT_PLAN.md → Overview / Reference source.

## Step 1 — Agent library

`~/.claude/agents/researcher.md` and `~/.claude/agents/reviewer.md` did not exist
(`~/.claude/agents/` itself did not exist). Both created verbatim from the spec.

Frontmatter as written:

```yaml
# researcher.md
name: researcher
description: Investigates a single scoped question against real source and returns a sourced report. Use proactively before writing any code that integrates a third-party SDK or API, before choosing between architectural approaches, and before any UX or vendor decision. Never used for implementation.
tools: Read, Grep, Glob, WebSearch, WebFetch
model: opus
effort: high
maxTurns: 60
color: blue
```

```yaml
# reviewer.md
name: reviewer
description: Reviews an uncommitted or recent diff against the stated plan and reports gaps. Use before declaring a task or sprint item done. Read-only — reports findings, never fixes them.
tools: Read, Grep, Glob, Bash
model: opus
effort: high
color: orange
```

**Action needed: restart Claude Code.** The `~/.claude/agents/` directory did not
exist at session start, so the directory watcher isn't covering it — `@agent-researcher`
and `@agent-reviewer` do not yet resolve in this session (not present in the current
available-agent-types list). This is expected per the bootstrap prompt's own caveat,
not a failure. Confirm after restart.

## Step 2 — Survey of this repo (Fr_user_update)

Was completely empty at session start: no files, no `.git`. Confirmed via `ls -la` and
`git status` (which returned "not a git repository"). No prior stack, no scaffolding —
consistent with GREENFIELD / AD-001 still open.

## Steps 3–5 — Structure created

- Directories: `docs/sessions/`, `docs/components/`, `.claude/hooks/`
- `PROJECT_PLAN.md`, `EXECUTION_PLAN.md`, `BACKLOG.md`, `CLAUDE.md` — all populated per
  the bootstrap spec (Overview, Constraints, decisions log, open questions/AD entries
  in PROJECT_PLAN.md; empty Sprint 1 table blocked on AD-001/AD-002 in
  EXECUTION_PLAN.md; empty BACKLOG.md table; minimal CLAUDE.md with hard rules and
  Commands/Coverage left as "pending stage B").
- Git initialised, branch `main`, remote `origin` set to
  `https://github.com/Osmantou/Fr_user_update`.
- `.gitignore` added (OS/editor files, `.env*`, `.claude/settings.local.json`) — no
  language-specific entries, since no stack is chosen yet.

## Step 6 — Verification

```
$ git status (before this report file was added)
On branch main
No commits yet
Changes to be committed:
        new file:   .gitignore
        new file:   BACKLOG.md
        new file:   CLAUDE.md
        new file:   EXECUTION_PLAN.md
        new file:   PROJECT_PLAN.md
```

Nothing from `../FIB` is staged or tracked — confirmed; `../FIB` is a separate git
repository entirely outside this working tree, and `.claude/settings.local.json` (the
only file referencing it) is gitignored.

`@agent-researcher` / `@agent-reviewer` do not yet resolve this session — restart
required, see Step 1.

## What failed / what's outstanding

- Nothing failed. Only open item is the required restart to pick up the new
  `~/.claude/agents/` directory.
- Stage B (toolchain/framework bootstrap, Commands/Coverage sections) is not run —
  correctly out of scope for Stage A, and blocked on AD-001 regardless.

## Next recommended step

Restart Claude Code to pick up the agent library, then run the AD-001 (stack) research
invocation — the standing order requires stack before external services (AD-002), and
no Sprint 1 task can be written until AD-001 is settled.
