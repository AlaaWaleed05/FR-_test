# Session: S1-01 — Stage B part 1, three-tier scaffold

Date: 2026-08-19 · Scaffolded `mobile/`, `backend/`, `backoffice/`. No feature code, no
Uqudo dependency (that is S1-02).

## 0. Previous session commit check

`git log --oneline -5` showed the AD-001 filing session
(`d263a7a docs: close AD-001 — record stack decision and file Uqudo component card`)
already committed, and `git status` reported a clean working tree. Nothing was pending;
no separate commit was needed before starting this session's work.

## 2. Toolchain resolution: report vs. actual current stable

| Tool | AD-001 report said | Actual current stable (resolved 2026-08-19) | Match? |
|---|---|---|---|
| Flutter | 3.47.x | **3.47.0** (stable channel, released 2026-08-12) — queried `storage.googleapis.com/flutter_infra_release/releases/releases_windows.json` | Matches |
| Spring Boot | 4.1.x | **4.1.0** (GA — no 4.1.1 exists yet; Maven Central metadata `lastUpdated` 2026-06-25) — queried `repo1.maven.org` `maven-metadata.xml` | Matches |
| Java | 21 LTS | 21 LTS (task-specified, not resolved from a registry) | N/A — fixed by task |
| Node | "≥ 22" | **24.19.0** "Krypton", current LTS (released 2026-08-03) — queried `nodejs.org/dist/index.json`; most recent release overall is v26.7.0 but that is not an LTS line | **Differs** — report undershot; see conflict below |
| React (backoffice) | React 19 | **19.2.8** (npm dist-tag `latest`) | Matches, more specific |
| Vite (backoffice, not named in report) | — | **8.2.1** (npm dist-tag `latest`) | New info |

## Machine conflict found and resolved

**Node.** The only Node runtime on this machine is a global install at
`C:\Program Files\nodejs`, v22.22.2, with no nvm/volta/fnm present to add a second
version in isolation. Pinning `backoffice/.nvmrc` to the actual current stable
(24.19.0) would not match what any command on this machine could actually run.
Per the task's instruction to report a pin conflict and stop rather than silently
resolving it, this was raised to the user directly (not decided unilaterally).

Options offered: (a) download an isolated, project-local copy of Node 24.19.0 that
does not touch the existing global v22.22.2 install (mirrors how `fvm` isolates
Flutter SDK versions without touching the machine's separate global Flutter install
at `C:\Users\DELL\flutter`, itself v3.38.7 — also untouched), (b) pin `.nvmrc` down to
the installed 22.22.2 instead, or (c) stop the Node/backoffice work entirely pending
the user's own resolution.

**User chose (a).** Node 24.19.0 (win-x64) was downloaded from `nodejs.org/dist` and
extracted to `C:\Users\DELL\.local-tools\node-v24.19.0-win-x64` — outside the repo, not
committed, and the existing global v22.22.2 was never touched. All `npm`/`node`
invocations for `backoffice/` in this session used that isolated copy's binaries
directly. `backoffice/.nvmrc` is pinned to `24.19.0`, matching what was actually used.

No other conflicts: the machine has no standalone JDK, but Android Studio's bundled
JBR is OpenJDK **21.0.8**, satisfying the 21 LTS requirement exactly with nothing to
install or change. No Maven is installed anywhere on the machine; the Spring Initializr
scaffold's own Maven Wrapper (`mvnw`/`mvnw.cmd`) self-bootstraps Apache Maven 3.9.16
into `~/.m2/wrapper` on first run — additive, not a conflict. `fvm` itself was not
previously installed; it was added via `dart pub global activate fvm` (an addition, not
a change to the existing global Flutter install).

## Scaffolding performed

- **mobile/**: `fvm flutter create --project-name mobile --platforms=android,ios mobile`
  (scoped to the two fixed platforms per PROJECT_PLAN.md; the framework's unscoped
  default also emits linux/macos/windows/web, which are out of scope for this product).
  `.fvmrc` committed at `mobile/.fvmrc` → `{"flutter": "3.47.0"}`. `android/app/build.gradle.kts`
  edited: `compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`, added
  `ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }`. iOS: no `Podfile` exists yet
  (CocoaPods has never run — normal on Windows, and expected to be generated during the
  S1-03 iOS build spike); `IPHONEOS_DEPLOYMENT_TARGET` was set to `15.0` in all three
  build-configuration blocks of `ios/Runner.xcodeproj/project.pbxproj`, the only iOS
  deployment-target location that exists pre-Podfile. iOS platform version must also be
  set in the Podfile once S1-03 generates one.
- **backend/**: generated via `start.spring.io/starter.zip`
  (`bootVersion=4.1.0`, `javaVersion=21`, `dependencies=web`, Maven, jar packaging,
  groupId `sd.gov.bank.fruserupdate`). A comment referencing OQ-006 was added in
  `pom.xml` next to the (absent) ojdbc dependency, per instruction — no ojdbc dependency
  was added. Note: Spring Boot 4.1's Initializr names the web starter
  `spring-boot-starter-webmvc` (renamed from `spring-boot-starter-web` in Boot 4, to
  disambiguate from `-webflux`) — this is the generator's own current default, not a
  manual substitution.
- **backoffice/**: `npm create vite@latest backoffice -- --template react-ts`
  (create-vite 9.1.2, Vite 8.2.1), then `npm install antd`. `src/main.tsx` edited to wrap
  the root render in `<ConfigProvider direction="rtl" locale={ar_EG}>` (import
  `antd/locale/ar_EG`) — the one line of RTL setup in scope, nothing else.
  `backoffice/.nvmrc` = `24.19.0`.

## 4. Detected facts per tier

### mobile/ (Flutter)
- Test command: `fvm flutter test`
- Lint/analyze command: `fvm flutter analyze`
- Build command: `fvm flutter build apk --debug` (Android debug); iOS build deferred to S1-03
- Codegen: none configured (no `build_runner` / code-generating packages added)
- Generated-file paths never to hand-edit: `.dart_tool/`, `.fvm/` (fvm's local SDK
  symlink cache — gitignored by the generator's own `.gitignore`), `build/`,
  `android/app/build/`, `ios/Flutter/Generated.xcconfig`,
  `ios/Runner/GeneratedPluginRegistrant.{h,m}`, `pubspec.lock` (managed by
  `flutter pub get`/`flutter pub upgrade`, not hand-edited)
- Toolchain pin: `.fvmrc` (fvm) → committed, `3.47.0`

### backend/ (Spring Boot / Maven)
- Test command: `./mvnw test`
- Lint/analyze command: **not present**. The bare `web`-only Initializr scaffold
  includes no static-analysis plugin (no Checkstyle/Spotless/PMD). Not fabricated —
  reported as absent rather than run.
- Build command: `./mvnw package -DskipTests` (or `./mvnw package`, which re-runs tests)
- Codegen: none (`spring-boot-configuration-processor` not added)
- Generated-file paths never to hand-edit: `target/`, `.mvn/wrapper/maven-wrapper.jar`
  (wrapper-managed binary — not present until wrapper first runs; the properties file
  that references it is source-controlled and hand-editable)
- Toolchain pin: `<java.version>21</java.version>` in `pom.xml` (inherited by
  `spring-boot-starter-parent`'s compiler plugin management). Built with JBR OpenJDK
  **21.0.8** (`C:\Program Files\Android\Android Studio\jbr`) — the only JDK on this
  machine; no standalone JDK install exists, none was added. Maven itself is not
  installed system-wide; `mvnw` self-bootstrapped Apache Maven **3.9.16**.

### backoffice/ (React + TypeScript + Vite)
- Test command: **not present**. Vite's `react-ts` template (create-vite 9.1.2) does
  not scaffold a test runner (no Vitest/Jest). Not fabricated — reported as absent.
- Lint command: `npm run lint` (runs `oxlint`, the template's own default — not ESLint)
- Build command: `npm run build` (runs `tsc -b && vite build`)
- Codegen: none
- Generated-file paths never to hand-edit: `dist/`, `node_modules/`
- Toolchain pin: `.nvmrc` → committed, `24.19.0`. See "Machine conflict found and
  resolved" above for why an isolated local Node install (outside the repo) was needed
  to actually run commands at that version.

## 5–7. Plan-file and card corrections

- PROJECT_PLAN.md: OQ-003 replaced with the "ANSWERED" text specified; Constraints
  paragraph rewritten to cover OQ-001/OQ-002/OQ-004 only and bind AD-002; "Ruled in /
  ruled out" line replaced to record AD-001 and the monorepo layout; Module map note
  replaced with "populated in Stage B part 2 (S1-05)". All four applied verbatim as
  specified in the task brief.
- docs/components/uqudo-sdk.md: the `targetSdk 36` Platform-floors warning replaced with
  the compileSdk/targetSdk-independence correction (marked `[UNVERIFIED]`); Open items'
  `targetSdk 36` line replaced with the S1-02 physical-device verification item; the Web
  SDK browser-matrix item deleted (its trigger condition — flip F1 or F2 firing — can no
  longer fire).
- EXECUTION_PLAN.md: S1-05 row added exactly as specified.

## 8. Verification — six gate commands (verbatim)

Two of the six do not exist in the generated scaffolds (backend lint/analyze,
backoffice test) — see §4 above. What exists was run and is pasted below unedited.

### mobile — `fvm flutter test`
```
00:00 +0: loading C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
```
Exit 0.

### mobile — `fvm flutter analyze`
```
Analyzing mobile...
No issues found! (ran in 16.9s)
```
Exit 0.

### backend — `./mvnw test`
```
[INFO] Running sd.gov.bank.fruserupdate.BackendApplicationTests
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.926 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Results:
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
Exit 0. (Full output also included first-run dependency-download noise from Maven
Central, omitted here as non-substantive.)

### backend — lint/analyze
Not present in the generated scaffold. No command was run; none is fabricated.

### backoffice — test
Not present in the generated scaffold. No command was run; none is fabricated.

### backoffice — `npm run lint`
```
> backoffice@0.0.0 lint
> oxlint

```
Exit 0 (oxlint prints nothing when it finds no issues).

## Builds — one per tier

### mobile — `fvm flutter build apk --debug`
```
Running Gradle task 'assembleDebug'...
Warning: Flutter support for your project's Gradle version (8.14.0) will soon be dropped. Please upgrade your Gradle version to a version of at least 9.1.0 soon.
Warning: Flutter support for your project's Android Gradle Plugin version (Android Gradle Plugin version 8.11.1) will soon be dropped. Please upgrade your Android Gradle Plugin version to a version of at least Android Gradle Plugin version 9.0.1 soon.
Warning: Flutter support for your project's Kotlin version (2.2.20) will soon be dropped. Please upgrade your Kotlin version to a version of at least 2.3.20 soon.
Running Gradle task 'assembleDebug'...                             66.4s
√ Built build\app\outputs\flutter-apk\app-debug.apk
```
Exit 0. The three Gradle/AGP/Kotlin version warnings are the generator's own default
template versions (Flutter 3.47.0's `flutter create` output as of 2026-08-19) — not
introduced by this session's edits, and left untouched per "generated starter, nothing
more." Worth a look in S1-05 or later if Flutter's own template hasn't caught up by
then.

### backend — `./mvnw package -DskipTests`
Produced `backend/target/backend-0.0.1-SNAPSHOT.jar` (19,867,681 bytes). Exit 0.

### backoffice — `npm run build`
```
> backoffice@0.0.0 build
> tsc -b && vite build

vite v8.2.1 building client environment for production...
✓ 1490 modules transformed.
dist/index.html                   0.46 kB │ gzip:  0.29 kB
dist/assets/index-D64VDMd1.css    4.10 kB │ gzip:  1.47 kB
dist/assets/index-DdgVh0tD.js   292.31 kB │ gzip: 98.26 kB
✓ built in 1m 12s
```
Exit 0.

## Pre-commit sanity check

`git add -A -n` staged 99 files with no `node_modules/`, `target/`, `build/`,
`.dart_tool/`, or `.fvm/` entries — each tier's own generator `.gitignore` correctly
excludes its build/cache output. `mobile/.fvm/` (fvm's per-project SDK symlink) is
excluded by the generator's own `.gitignore` line 48 (`.fvm/`).

## What could not be determined / deferred

- iOS `Podfile` platform pin: no Podfile exists yet (no CocoaPods run on this Windows
  machine); `IPHONEOS_DEPLOYMENT_TARGET` was set in the `.pbxproj` only. Must also be
  set once S1-03 generates a Podfile.
- backend lint/analyze and backoffice test commands: genuinely absent from the
  generated scaffolds, not a gap in this session's execution.
- Flutter's own Gradle/AGP/Kotlin template versions triggered "will soon be dropped"
  deprecation warnings during the Android build; not fixed here (out of scope — no
  version beyond the generator's own defaults was introduced).

## Status

S1-01 set to ✅ in EXECUTION_PLAN.md: all six requested gates either ran clean or were
confirmed genuinely absent from the generator's own output (not skipped, not
fabricated), and all three tiers built successfully once each.
