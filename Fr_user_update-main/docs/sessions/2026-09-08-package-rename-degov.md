# S8-06 — Package namespace rename, de-`.gov`

**Date:** 2026-09-08 · **Closes:** BL-081, BL-079 · **Clears:** BL-082 blocker (1) ·
**Road map:** §2.1, §2.2 · **Machine:** Windows (DELL)

The backend shipped under `sd.gov.bank.fruserupdate`. `sd.gov.bank` denotes a **government**
bank; the client is the Sudanese French Bank, a private commercial bank. The Android app shipped
under Flutter's template `com.example.mobile`, which Play rejects outright and which **locks
permanently on first upload**. Both are now **`com.sfbank.bayanati`** — product-owner decision
this session: `com` commercial, `sfbank` the bank's public domain `sfbank-sd.com`, `bayanati` the
app name «بياناتي».

Zero functional change. Display names untouched — the launcher label stays «بياناتي».

---

## 1. The decision, and what it settles

BL-081 listed three decisions "the bank owns, not the delivery team". (1) the app name was settled
2026-09-07. This session settles the other two, and settles them as **one identifier shared by
both tiers** rather than two:

| BL-081 decision | Settled as |
|---|---|
| (2) Play Store package identifier | `com.sfbank.bayanati` — same value, so BL-079 closes here too |
| (3) Java package root | `com.sfbank.bayanati` — the row had only ever speculated `sd.sfbank.*` |

Recorded in BACKLOG and road-to-production rather than as a new AD: PROJECT_PLAN:315 records that
`backend/` internals stopped being open under AD-002 at S3-01, so the package *name* was a backlog
item, not an open architecture decision. Nothing was settled in passing.

---

## 2. Two corrections to the task brief, found against source

Both changed what the work actually was, so they are recorded before the work.

**The brief said mobile was namespaced `sd.gov.bank`. It was not.** Mobile carried Flutter's
untouched template id `com.example.mobile` — in `build.gradle.kts` (`namespace` and
`applicationId`), in `MainActivity.kt`'s package and directory, and in six
`PRODUCT_BUNDLE_IDENTIFIER` entries in `project.pbxproj`. The target value was unaffected; the
starting point was not what the brief assumed, and a rename script written to the brief's premise
would have changed nothing on the mobile side and reported success.

**A plain `sd.gov` grep is not sufficient, and this is the finding worth carrying forward.**
`docs/components/uqudo-sdk.md:571` held the package as a **filesystem path** —
`backend/src/main/java/sd/gov/bank/fruserupdate/uqudo/stub/` — with slashes, not dots. The
pre-change blast-radius grep searched `sd\.gov` and never saw it. It was caught only by the
**post-change** grep, which searched all three encodings (`sd.gov`, `sd/gov`, `sd\gov`). A live
component card would otherwise have shipped pointing at a directory that no longer exists.

---

## 3. Blast radius, measured

| Where | Count |
|---|---|
| `backend/src/**/*.java` | **456** files (347 main + 109 test), 1562 occurrences |
| Backend package directory trees | 2 (`git mv`, main + test) |
| `backend/pom.xml` | `<groupId>` + 2 JaCoCo `<exclude>` paths |
| `V0035__app_notification_outbox.sql` | 1 comment naming an FQN |
| `mobile/android/app/build.gradle.kts` | `namespace` + `applicationId` + the template `TODO` |
| `MainActivity.kt` | package declaration + directory |
| `mobile/ios/.../project.pbxproj` | 6 × `PRODUCT_BUNDLE_IDENTIFIER` |
| `mobile/lib/core/network/manifest_dto.dart` | 3 doc comments naming backend FQNs |
| Live docs | CLAUDE.md, PROJECT_PLAN, EXECUTION_PLAN, BACKLOG, 3 component cards, road-to-production |
| `.claude/settings.json` | 1 stale Bash-allowlist path — see the note below |

**Clean, verified by grep:** `backoffice/`, `infra/`, `db/`, `docker-compose.yml`,
`backend/Dockerfile`.

**`.claude/settings.json` is a fifth scope item, stated rather than slipped in.** The brief named
four (backend root, Android id/namespace, iOS bundle id, live-doc references); this is a
permissions file, so it gets said out loud. Line 70 held a Bash-allowlist entry naming
`backend/src/test/java/sd/gov/.../AppSchemaConnectivityIntegrationTest.java`. Only the path
changed — same `awk` invocation, so nothing is broadened or narrowed. It was updated rather than
left because an allowlist entry pointing at a file that no longer exists is dead weight that would
silently stop matching. Raised by the reviewer; kept deliberately.

**Why no Spring wiring broke:** there is no `@ComponentScan`, no `scanBasePackages`, no
`basePackageClasses`, no reflective `Class.forName`, and no package string in any
`application*.properties`. Component scanning is same-package auto-configuration from
`BackendApplication`, so it followed the move. `artifactId` stays `backend`, so
`target/backend-0.0.1-SNAPSHOT.jar` is unchanged and `backend/Dockerfile`'s hardcoded COPY still
matches — checked rather than assumed, because a groupId change that silently renamed the jar
would have broken the image build with no test failing.

### Deliberately not changed

- **42 historical session reports under `docs/sessions/` (295 occurrences).** Product-owner
  decision this session. They paste verbatim gate output from the days they describe
  (`[ERROR] src\main\java\sd\gov\bank\fruserupdate\…`); rewriting them would make a dated report
  claim a tree that never existed. The residue is stated and counted, not hidden.
- **External `.gov` URLs** — `designsystem.gov.ae`, `designnotes.blog.gov.uk`, `ofac.treasury.gov`,
  `federalregister.gov`. Real citations in research reports. "Zero `.gov` anywhere" was therefore
  never the right acceptance bar and was not used as one.
- **`Info.plist` `CFBundleName`/`CFBundleDisplayName`** (`mobile`/`Mobile`) — display names, out of
  scope; they belong with BL-052's iOS pass. Flagged rather than silently fixed.

---

## 4. Two execution hazards, and how each was handled

**CRLF.** Working-tree Java files are CRLF (`core.autocrlf=true`; `.gitattributes` pins `eol=lf`
for `*.sh`/`*.sql` only). The `sed` pass converted all 456 files to LF, which fails Spotless's
whole-file format check. `./mvnw spotless:apply` restored CRLF before the gate, and
`spotless:check` inside `verify` is the arbiter that proves it — see §5.

**Import reordering is not a functional change.** google-java-format sorts imports, and
`com.sfbank.*` sorts *before* `java.*` where `sd.gov.*` sorted *after*. Every backend file's import
block therefore moves position. Representative hunk:

```
-package sd.gov.bank.fruserupdate.accountcheck.service;
+package com.sfbank.bayanati.accountcheck.service;

+import com.sfbank.bayanati.accountcheck.domain.AccountCheckContinuation;
+import com.sfbank.bayanati.audit.domain.AuditEventWriter;
 import java.time.Clock;
 ...
-import sd.gov.bank.fruserupdate.accountcheck.domain.AccountCheckContinuation;
```

Same imports, same count, different position. Worth stating because it is what makes the diff look
larger than it is.

**Diff shape** — the strongest single argument that nothing functional moved:

```
457 files renamed (R/RM)   14 files modified (M)   0 untracked
471 files changed, 1680 insertions(+), 1663 deletions(-)
```

The 14 content-only modifications are exactly the build files, the SQL comment, the Dart doc
comments and the docs. No `.java` file appears as anything but a rename.

---

## 5. Gates — verbatim

### backend — `./mvnw verify -Pdb-integration-test` (the real coverage gate per CLAUDE.md)

```
[INFO] Results:
[INFO]
[INFO] Tests run: 1091, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 456 files clean - 0 needs changes to be clean, 0 were already clean, 456 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  06:07 min
[INFO] Finished at: 2026-09-08T08:26:30+02:00
[INFO] ------------------------------------------------------------------------
```

`Spotless.Java is keeping 456 files clean` is the CRLF proof: every renamed file passed the
whole-file format check.

### mobile — `fvm flutter analyze`

```
Analyzing mobile...
No issues found! (ran in 313.9s)
```

### mobile — `fvm dart run tool/check_coverage.dart` (tests + the enforced 80% gate)

```
03:31 +504: All tests passed!
Line coverage: 84.46% (3739/4427 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

`fvm flutter test` was also run standalone ahead of this and reported `+504: All tests passed!`.

### mobile — `fvm flutter build apk --debug`

```
Running Gradle task 'assembleDebug'...                            302.8s
√ Built build\app\outputs\flutter-apk\app-debug.apk
```

The three Gradle/AGP/Kotlin "support will soon be dropped" warnings in that output are
pre-existing version notices, unrelated to this change.

---

## 6. Build is not the bar — install and launch

A namespace rename that compiles can still be wrong. Four checks, each answering something a
green build does not.

**The built artifact carries the new id** — read from the APK, not from the source that produced
it:

```
$ aapt2 dump badging build/app/outputs/flutter-apk/app-debug.apk
package: name='com.sfbank.bayanati' versionCode='1' versionName='1.0.0' ...
launchable-activity: name='com.sfbank.bayanati.MainActivity'  label='' icon=''
```

This is the check that proves `namespace`, the Kotlin package and the manifest's relative
`android:name=".MainActivity"` all resolved to the same place.

**It installs as a NEW app, which is the whole point of the row:**

```
=== packages BEFORE install ===
package:com.example.mobile
=== adb install ===
Performing Streamed Install
Success
=== packages AFTER install ===
package:com.sfbank.bayanati
package:com.example.mobile
```

Both coexist. `dumpsys package` confirms it registered as a first install, not an update:
`firstInstallTime=2026-09-08 08:35:55` equals `lastUpdateTime`. This is exactly the irreversibility
BL-079 warned about, observed rather than argued.

**It launches, and reaches its own UI:**

```
$ adb shell am start -n com.sfbank.bayanati/.MainActivity
Starting: Intent { cmp=com.sfbank.bayanati/.MainActivity }
$ adb shell pidof com.sfbank.bayanati
6041
$ adb shell dumpsys activity activities | grep topResumedActivity
topResumedActivity=ActivityRecord{130283869 u0 com.sfbank.bayanati/.MainActivity t8}
$ adb logcat -d -b crash
(empty)
```

The screenshot shows the real Arabic RTL first screen — header «بيانات الحساب», the offline state
«تعذر الاتصال، الرجاء التأكد من الاتصال بالإنترنت ثم أعد المحاولة» and the «أعد المحاولة» retry
button, on the S8-05 surface with the DEBUG banner. **The offline state is expected and is not a
regression:** no backend is running against this emulator. It is in fact stronger evidence than a
blank splash would be — it proves Dart booted, routing resolved, Arabic RTL rendered and the
network layer executed a real call.

**One honest note on the first capture.** The first screenshot caught a `System UI isn't
responding` ANR dialog. That is `com.android.systemui`, a different process, starved because the
Maven and Gradle builds were running concurrently on the same machine — our app's own surface was
rendering behind it and `topResumedActivity` was already ours. It was dismissed, the machine was
left to settle, and the capture above was retaken. Recorded rather than quietly discarded, because
a discarded screenshot is exactly the kind of thing a reader should be able to see the reason for.

---

## 7. Post-change grep — verbatim

```
########## 1. OLD BACKEND ROOT — any form (sd.gov / sd/gov / sd\gov), whole repo ##########
./BACKLOG.md:86              — deliberate: BL-081's closed row quotes its own original text
./docs/road-to-production.md:216 — deliberate: "The backend's package root WAS `sd.gov...`"
./PROJECT_PLAN.md:682        — deliberate: OQ-018's reasoning about the root it was inferred from
(no other hit outside docs/sessions)

########## 2. docs/sessions residue — deliberate, counted ##########
files: 42
occurrences: 295

########## 3. OLD MOBILE ID com.example — outside docs/ and generated trees ##########
./mobile/android/app/build.gradle.kts:27  — deliberate: the new comment says what it REPLACED
(BACKLOG BL-070/BL-079/BL-082 hits are historical quotations inside closed rows)

########## 4. fruserupdate outside docs/sessions ##########
(same three deliberate documentary hits as #1; no code, no config)
```

Every surviving occurrence outside `docs/sessions/` is a deliberate documentary reference to what
the name **used to be**, inside a row or sentence that says so. No code and no config retains the
old root in any encoding — re-verified for the slash and backslash forms specifically, after they
were the forms that hid `uqudo-sdk.md`.

---

## 8. Review

`@agent-reviewer` against the full uncommitted diff. **No blockers. The zero-functional-change
claim holds against the diff**, verified mechanically rather than asserted: the reviewer extracted
every `+`/`-` line in the Java diff and filtered out package, import and comment lines. The only
survivors were 12 fully-qualified type references in test code (`ArgumentCaptor<...AuditArtifact>`,
`mock(...ProfileRepository.class)`, `...Urgency.INTERACTIVE`, three exception `.class` literals) —
each identical apart from the root.

Two files had unequal `+`/`-` counts, and both are Spotless reflows caused by the **shorter** name:
`otpverification/domain/OtpVerificationRepository.java` (4/5) and
`contactchannels/service/ContactChannelsServiceTest.java` (20/21), where `.allMatch(` now joins its
lambda. The predicate is otherwise byte-identical. Worth recording because an unequal line count is
exactly what a reviewer should stop on, and the explanation is the rename, not an edit.

Also independently confirmed: all 456 working-tree Java files are pure CRLF (0 mixed, 0 pure-LF);
both JaCoCo excludes kept the slash form; `artifactId` unchanged so `Dockerfile:39` still matches;
no generated file touched; no secrets; OQ-018 annotated but not settled. And one useful negative
check a text grep would have missed: **there is no `logging.level` entry anywhere in backend
resources**, so no package-scoped logger config could have been left pointing at the old root.

| # | Finding | Disposition |
|---|---|---|
| 1 | **SHOULD FIX** — the 5-line comment this session added to `build.gradle.kts` shifted the release `signingConfig` block from 41-44 to **45-48**, invalidating that citation in `docs/road-to-production.md` §2.3 and `BACKLOG.md` BL-082 | **Fixed.** Both updated to `45-48`, re-verified against the file: `release {` at 45, `signingConfig` at 48. A real self-inflicted defect — this diff broke a reference in a doc this same diff edits |
| 2 | **NOTE** — a stray CR introduced at `PROJECT_PLAN.md:684` in an otherwise pure-LF file. Invisible in `git diff` because `core.autocrlf=true` normalises it | **Fixed.** File is back to 0 CR. Committed blob would have been unaffected, but the working tree would have carried one odd line forever |
| 3 | **NOTE** — `.claude/settings.json:70` is outside the four scope items the brief listed | **Kept, and stated as a fifth scope item** (see §3). Same `awk` command, path only; it neither broadens nor narrows what is permitted. An allowlist entry pointing at a path that no longer exists is dead, so leaving it stale was the worse option — but the reviewer is right that a permissions file changing under a rename task must be called out, not slipped in |
| 4 | **NOTE** — `docs/road-to-production.md` §2.3 still read "Four blockers" after item 1 was struck through | **Fixed** — "Four blockers, one now cleared". `BACKLOG.md` BL-082 already said "Three blockers remain" |
| 5 | **NOTE** — BL-081's closure text said "both component cards"; three changed | **Fixed** — "all three component cards". The third is `uqudo-sdk.md`, the slash-form card from §2 |

Findings 1 and 5 are both downstream of the same thing: this session edited docs that describe the
very files it changed. That is worth remembering next time a rename touches its own documentation.

---

## 9. Residual risk, filed not absorbed

**Uqudo tenant binding to the application id.** Uqudo's tenant may restrict the SDK licence to an
allowed bundle/application id. Nothing in this repo couples them — no package name is sent in the
token request and there is no Uqudo entry in the Android build — so there is no code change owed.
But stages 8 and 10 were **not** exercised under the new id this session, so a first real document
scan under `com.sfbank.bayanati` is the outstanding confirmation. Filed to BACKLOG rather than
claimed as covered.

**iOS is [UNVERIFIED] by build.** The `PRODUCT_BUNDLE_IDENTIFIER` change is correct by inspection
and matches Android, but iOS is not compiled on this machine (CLAUDE.md, BL-052). It ships
unproven and is labelled as such in BL-079 and road-to-production §2.2 as well as here.

---

## 10. Plan files updated

- **BACKLOG** — BL-081 and BL-079 closed 2026-09-08 with the settled value; each keeps its original
  text after the closure note, per this file's convention. BL-082's blocker (1) struck through and
  marked cleared; (2) debug signing, (3) AAB and (4) app identity stand unchanged.
- **EXECUTION_PLAN** — S8-06 added; S1-08's JaCoCo pattern example corrected to
  `com.sfbank.bayanati.*.domain`.
- **CLAUDE.md** — the same JaCoCo dot-separated pattern example.
- **PROJECT_PLAN** — OQ-018 annotated: the root it reasoned from has been renamed, and why that
  does not change its conclusion.
- **road-to-production** — §2.1 and §2.2 marked done; §2.3 blocker (1) cleared.
