# 2026-09-08 — Local Android emulator for design review (tooling only)

**Type:** machine/tooling setup, guided. **No project source changed** — see §7.
**Purpose:** let the product owner SEE the app's design (surface, splash, RTL, layout, motion)
on a mid-range device profile rather than only on the 2 GB below-floor Huawei.

---

## 1. Machine state as found (nothing installed to discover this)

Everything needed was **already present**. This was not a from-scratch install.

| Component | State |
|---|---|
| Android Studio | ✅ `C:\Program Files\Android\Android Studio` |
| Android SDK | ✅ `C:\Users\DELL\AppData\Local\Android\Sdk` (`ANDROID_HOME` unset, path resolved directly) |
| Emulator + cmdline-tools | ✅ both present |
| `flutter doctor` | ✅ **No issues found** — Flutter 3.47.0 (the `.fvmrc` pin), Android SDK 36.1.0 |
| Existing AVD | ✅ `Medium_Phone_API_36.1` |
| System image | ✅ `android-36.1/google_apis_playstore/x86_64` (the only one installed) |
| **Hardware acceleration** | ✅ **WHPX installed and usable**; Hyper-V enabled |

`emulator-check accel` → `WHPX(10.0.19045) is installed and usable.` Acceleration is the
single fact that decides whether a local emulator is tolerable, and it is present.

**Machine capacity — the real constraint:**

- **CPU: Intel i7-6600U, 2 cores / 4 threads** (2016 low-power ultrabook part). Permanent limit.
- **RAM: 7.89 GB total; 0.32–1.31 GB free** during the session (VS Code ×3, Chrome, node,
  two `claude`, stale `dart`/`flutter_tester`).
- **Disk at session start: 12.74 GB free of 237.81 GB (95 % full).** Storage is a **Samsung
  NVMe SSD**, which materially softens paging cost.

## 2. The API-33 decision — deliberately NOT followed, with evidence

The brief specified an ~API 33 image so the PO would see the "rich visual tier". **No image was
downloaded.** Reason, from the source rather than preference:

`mobile/lib/core/theme/app_theme.dart:43`
> *"That is also why this needs no device tiering, unlike the splash — a flat fill costs the same
> on Impeller and on the legacy renderer, so a judgement made on the API-28 pilot handset
> transfers to every device."*

**There is no runtime visual-tier branch in this app.** The splash's apparent "tiering"
(`launch_screen.dart:206`) is likewise a *baked-in* choice — a gentle scale **up**, chosen because
scale-down is the variant that shows first-run shader compilation as a stutter on the legacy
renderer — applied identically on every device. A grep for `Impeller|lowEnd|deviceTier|sdkInt|
physicalMemory` across `lib/` returns only comments, no branch.

Therefore the visual tier is not unlocked by an API level; the installed API 36.1 image already
provides a modern renderer. Downloading a ~3 GB image onto a 95 %-full disk would have bought
nothing visually and worsened the actual bottleneck. **Recorded as a deviation from the brief so
it is not mistaken for an oversight.**

## 3. Profile configured

Existing AVD reused. It was already the mid-range shape the brief asked for
(`medium_phone`, 1080×2400 @ 420 dpi, x86_64, Google APIs — Pixel-5/6 territory).

| Setting | Before | After | Why |
|---|---|---|---|
| `hw.ramSize` | 2048 | **2048** (via 3072) | See below |
| `fastboot.forceFastBoot` | `yes` | `yes` | Already enabled; no change needed |
| `hw.cpu.ncore` | 2 | 2 | Correct on a 2-core host |

`config.ini` was backed up to `config.ini.bak-20260908` before any edit.

**RAM was raised to 3072 (the brief's 3–4 GB floor), then returned to 2048 on an explicit PO
decision.** The reasoning is counterintuitive and worth keeping: physical RAM, not commit, was
exhausted — page file peak usage was **2.03 GB of 7.89 GB allocated** with **3.81 GB commit still
free**, so enlarging the page file would have added headroom to an already-unused pool. A 3 GB
guest that does not fit in physical RAM pages continuously; a 2 GB guest that fits does not. Since
the app has no device tiering (§2), the design renders **pixel-identically** at 2048. Verified in
guest: `MemTotal: 2014484 kB`.

## 4. The real defect found: the AVD was consuming 13 GB and its /data was full

First install attempt failed:

```
adb: failed to install app-debug.apk:
Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location]
```

Guest `/data` was **89 % full — 5.0 G used of 5.8 G, 674 M free**, against a 250 MB debug APK.
Host-side breakdown of `Medium_Phone.avd` (13 G total):

| Item | Size | Disposition |
|---|---|---|
| `userdata-qemu.img.qcow2` | 8.6 G | reclaimed by `-wipe-data` |
| `snapshots/` | 2.1 G | deleted (invalidated by the RAM change and the wipe) |
| **`tmpAdbCmds/`** | **1.2 G** | **deleted — 2,675 orphaned `adbcommand*` staging files** |

After cleanup + `-wipe-data`: guest `/data` **13 % used, 4.9 G free**; host disk **12.74 GB →
22 GB free**. A 95 %-full SSD is itself a plausible contributor to the sluggishness that prompted
the PO's "it is so slow" — this was not only an install blocker.

**Incidental:** `adb shell df /data` returned `df: 'C:/Program': No such file or directory` —
Git Bash MSYS path mangling, the same class of failure recorded at S7-07 for `mktemp`/`aws.exe`.
Fixed with `MSYS_NO_PATHCONV=1`. Any future adb-shell work from Git Bash needs that export.

## 5. Proof the app runs against AWS staging

Build (current **working tree**, which carries the uncommitted design batch — not a commit):

```
fvm flutter build apk --debug \
  --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net
...
Running Gradle task 'assembleDebug'...                            244.7s
√ Built build\app\outputs\flutter-apk\app-debug.apk
[exited with code 0]
```

**Connectivity verified from inside the guest, not assumed** (the brief required this — an
emulator's network differs from a phone's):

- `nc -z 52.222.250.155 443` → **OK**; `nc -z 52.222.250.187 443` → **OK** (CloudFront A records)
- First attempt returned `Network is unreachable` — transient, the Wi-Fi stack was still coming
  up. Retested after the route table settled. Recorded because a single failed probe here would
  have been misread as a blocked network.
- `curl` is absent from this image and `ping` cannot resolve from the adb shell; `nc` is the
  working tool.

**Install + launch:**

```
adb install -r app-debug.apk   → Performing Streamed Install / Success   (53s)
adb shell pidof com.example.mobile → 2698
topResumedActivity=ActivityRecord{... com.example.mobile/.MainActivity t7}
```

**End-to-end evidence — the strongest available, and stronger than a reachability probe:**
the app rendered the account-entry screen (بيانات الحساب) and its **الفرع branch dropdown
populated with a real branch, «الجنيد»**. Branch is a **server-supplied reference list**
(CLAUDE.md: reference lists are never hardcoded), so that value can only have come from the
deployed backend. Chain proven: emulator → CloudFront → backend → reference list → Arabic RTL
render. The host-side manifest fetch corroborates it (25 branches, 151 admin divisions,
`catalogHash 248bdcee…`).

Design confirmed visually on screen: SFB brand blue with the white camel-rider emblem on the
splash; on the entry screen, correct RTL (right-aligned title and fields), clean Arabic glyphs,
and the tinted-canvas/white-container treatment reading as intended — the containers separate
from the page.

**Timings measured on this machine:**

| Step | Time |
|---|---|
| Cold boot (first, 3072 MB) | 87 s |
| Quick boot (2048 MB, snapshot) | 47 s |
| Cold boot after `-wipe-data` | 182 s (one-off) |
| **Debug APK build** | **244.7 s** |
| Install (250 MB) | 53 s |
| Launch → interactive entry screen | ~12 s |

## 6. Verdict — is local good enough, honestly?

**Yes, for looking at design — now that the disk is fixed.** Not "fast", but adequate, and
better than the online alternative *for this specific purpose*.

The PO asked mid-session whether to switch to an online tool. The decisive fact:

> **The 245 s build is the dominant cost, and no online tool removes it.** Every service —
> AWS Device Farm, Appetize, BrowserStack — needs an APK built locally first, then a 250 MB
> upload *on top*. Switching online would have made the loop slower, not faster.

And the local loop has something no online tool can offer: **`flutter run` gives hot reload**,
so a design change appears in **under two seconds with no rebuild at all**. For judging
successive design batches — the stated purpose — that is decisive.

**AWS does have a ready option — AWS Device Farm** (real physical phones, browser-based Remote
Access). Not recommended here: it bills ~$0.17/device-minute after a limited free trial, needs
IAM/project/upload setup, and would add a billing surface to the **personal-root-owned account
already flagged as a handover problem (BL-072)**. If an online tool is ever wanted, **Appetize.io
is the better fit for *looking*** (browser URL, no AWS account, shareable link for colleagues);
Device Farm is better for *testing*.

**Limits the PO must know:**

- **Uqudo scan and liveness cannot run on this emulator — it is x86_64 and the SDK is arm-only.**
  Expected, and not what this is for. Every screen's *look* can still be judged.
- **OTP cannot be received** (no real SMS to an emulator).
- **Staging still runs the core-banking stub (BL-089).** Only the seeded synthetic accounts
  `0000001001`–`0000001030` pass Stage 1a; a real account number returns "not found". Each is
  **single-use** (a profile is keyed by account number and never deleted by design), so thirty
  accounts mean thirty walks. `0000000001` is already burned.
- The machine will never be quick. Keep Chrome and spare VS Code windows closed while judging.

## 7. Repo impact — none, and a caveat

**No project source, test, config or generated file was changed.** All changes were to the PO's
machine: `~/.android/avd/Medium_Phone.avd/config.ini` (backed up) and deletion of AVD junk. The
built APK lives under `mobile/build/`, which is gitignored (`mobile/.gitignore:33 /build/`).

**Caveat, stated because it is not this session's doing:** `git status` gained `M BACKLOG.md`,
`M docs/journeys/customer.md` and `?? docs/sessions/2026-09-08-mobile-pilot-build.md` *during*
this session, and `?? strartup.mp4` disappeared. Those are from parallel work, **not from here**.
Only this report was committed, by explicit path.

No test or analyze gate was run: no tier was touched, so there is nothing for a gate to prove.
No `@agent-reviewer` pass for the same reason — there is no diff to review.

## 8. The repeatable loop

Run from `mobile/`. `fvm` is not on PATH on this machine:
`C:\Users\DELL\AppData\Local\Pub\Cache\bin\fvm.bat`.

**Boot the emulator** (~15–47 s from snapshot):

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Medium_Phone_API_36.1 \
  -netdelay none -netspeed full -no-boot-anim &
```

**Preferred — hot-reload loop for judging a design batch.** Builds once, then `r` reloads in
under two seconds, `R` restarts, `q` quits:

```bash
export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"
fvm flutter run -d emulator-5554 \
  --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net
```

**Alternative — rebuild and reinstall after a batch lands** (use when starting from a clean tree):

```bash
export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"
fvm flutter build apk --debug \
  --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r \
  build/app/outputs/flutter-apk/app-debug.apk
```

**Housekeeping — `tmpAdbCmds` regrows.** It reached 1.2 GB / 2,675 files. On a disk this tight,
clear it when space runs low:

```bash
rm -rf ~/.android/avd/Medium_Phone.avd/tmpAdbCmds
```

**If a later install fails with `INSUFFICIENT_STORAGE`**, the guest `/data` has filled again;
add `-wipe-data` to the boot command once (costs a ~180 s cold boot, reclaims several GB).
Inspect it with `MSYS_NO_PATHCONV=1 adb shell df -h /data` — without that export, Git Bash
mangles the path.
