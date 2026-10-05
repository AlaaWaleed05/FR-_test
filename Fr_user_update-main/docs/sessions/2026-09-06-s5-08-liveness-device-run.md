# S5-08 liveness device run — stages 10–12 and a full 1→12 walk on real hardware

**Date:** 2026-09-06
**Device:** Huawei Y5 2019 (AMN-LX9 / AMN-L29), Android 9 / API 28, **armeabi-v7a (32-bit only)**, serial 7YBNU19B09320980
**Build:** debug APK from **6d09d15** (clean tree), `--dart-define=REFERENCE_API_BASE_URL=http://172.20.10.2:8080`
**Backend:** local jar **rebuilt from 6d09d15**, `fru.uqudo.client=http` against **the project's own real Uqudo tenant** [label corrected 2026-09-06 — see the note below]; `fru.civil-registry.client=stub`, `fru.core-banking.client=stub`, all messaging `stub`. Run config lived only in a gitignored file outside the repo and referenced credentials as `${UQUDO_CLIENT_ID}`/`${UQUDO_CLIENT_SECRET}` env placeholders — no secret value was read, echoed, logged or written anywhere.

> **Correction, 2026-09-06 — the tenant label above was wrong.** As written on the day, that line read "the **real (FIB-borrowed) Uqudo tenant**". The credentials this run actually used were the project's **own** Uqudo tenant, not FIB's borrowed one; the "FIB-borrowed" wording was carried forward out of habit from the sessions that predate the project acquiring its own tenant. **Nothing else about this run changes** — not its evidence, its timings, its findings or its conclusions; only whose tenant it was. Together with the other device run, this is what answers OQ-010. Filed by docs/sessions/2026-09-06-tenant-reconciliation.md.

**Mode:** guided live — Claude Code prepared/built/installed/observed; the product owner (PO) drove the phone.
**Profile:** account `0000000200`, profile `cc4faa29-…b394c`, reference **FRU-000000004**.

## Outcome

**Clean run — S5-08 → ✅.** The whole journey walked continuously on hardware, stages 1→12, in ~11 minutes with no defect. **Liveness passed against the real Uqudo tenant for the first time ever**, and the face-SDK cancel path ran on hardware for the first time, yielding the verbatim SDK code this run existed to capture. Two accepted non-results (R-016, SDN_ID) and one narrowed gap (uploaded-signature byte size) are recorded below. No fix session is owed.

The single most valuable output: **the real face-SDK cancel code is `USER_CANCEL`** — the same string the document scanner emits, confirming the app's assumption rather than correcting it.

## STEP 1 — network, proven before anything was built

Laptop and phone on the **iPhone hotspot** (172.20.10.0/28): laptop **172.20.10.2**, phone **172.20.10.3**, gateway 172.20.10.1, DHCP.

A first `Get-NetIPAddress` reading showed Wi-Fi as **192.168.0.5** — the stale failed-LAN address the brief warns against baking in. That was a pre-DHCP-lease reading; the authoritative address is 172.20.10.2, confirmed by the gateway and `PrefixOrigin=Dhcp`. Nothing was built until that was settled.

- **ICMP is a false negative here, again:** phone→laptop `ping` returned **100% packet loss**, while a phone→laptop `curl` on 8080 returned **`http_401`** from `/api/v1/auth/me`. S5-07's finding reproduces exactly.
- Stronger than a 401: the phone fetched `/api/v1/reference/manifest` live and got real content (`branch` v1 / 25 items, `admin_division` v1 / 151 items). Real API traffic, not just a reachable socket. This mattered because a populated branch dropdown alone would **not** have proven a live fetch — reference lists are cached on device, so the dropdown could have been S5-07's cache.

## STEP 2 — setup, and one defect caught before the run

**Pre-run finding (fixed before starting): the backend jar was stale.** `backend-0.0.1-SNAPSHOT.jar` was built 02:46:37; commit **c75dfb8** (02:54:19) — *"BL-041/AD-008: a device-less re-entry no longer inherits identity artifacts"* — changed backend code after it. That jar would have run stale against exactly the identity-artifact behaviour this walk exercises. This is the S5-01 lesson in a new guise: **a jar's mtime must be checked against the last commit touching its tier, not just against HEAD's docs commits.** Rebuilt from 6d09d15 before starting.

- Docker/Postgres 18 already up; schema migrated as `fru_migrator` (`flyway:migrate`) → **V0063**, matching the newest migration on disk.
- Port 8080 free before launch (PID-14832 lesson clear); the started jar held it as PID 7412.
- APK carries `armeabi-v7a`, `arm64-v8a`, `x86_64`; 11 native `.so` files under `armeabi-v7a`, so the 32-bit handset is covered. **The brief's "real arm64 Huawei" is wrong** — `ro.product.cpu.abilist=armeabi-v7a,armeabi`, 32-bit only, exactly as S5-07 recorded.

### The validity question — settled affirmatively, and not on the weak argument

`fru.uqudo.client=http` could not be proven from startup alone: the property has no default and fails startup if unset, but that argument cannot distinguish `http` from `stub`, and this build exposes no actuator and logs no client-selection line. The run was therefore **not** declared valid on config-file reasoning. Two independent live proofs settled it:

1. **The stub seed took effect.** Account `0000000200` — seeded only in the run-config file — produced a profile. The file was definitively loaded.
2. **The artifact table is self-controlling.** Real captures sit beside a known stub placeholder:

| kind | byte_size | body stored | source |
|---|---|---|---|
| `doc_front` | 660,421 | yes | real passport capture |
| `doc_front_frame` | 1,726,304 | **no (NULL)** | metadata only — AD-004 |
| `portrait_uqudo` | 156,936 | yes | real extracted portrait |
| `face_audit_trail` | 406,272 | yes | **real face image fetched from the live tenant** |
| `portrait_registry` | 32 | yes | stub placeholder — the control |

A 32-byte registry placeholder beside a 406 KB face image retrieved by JWS reference is decisive: a stub client cannot produce the latter. **Real tenant confirmed.**

**A correction I made before filing it as a finding:** `doc_front_frame`'s 1,726,304 initially looked like a contradiction of AD-004 ("raw capture frames excluded deliberately") and of S5-07's "doc_front_frame null". It is not. `byte_size` is metadata — the size Uqudo reported — while `body IS NULL`. AD-004 is honoured exactly; S5-07's "null" meant the body. The apparent finding was my reading of the wrong column.

## STEP 3 — the full walk, stages 1→12

Continuous, no stage failed to hand off. Audit chain (UTC):

| Time | Event | Note |
|---|---|---|
| 08:50:57 | `session_created` | account 0000000200, branch 19 |
| 08:50:57 | `otp_issued` ×3 + `notification_dispatched` ×3 | sms, whatsapp, email |
| 08:51:47 / 08:52:01 / 08:52:12 | `otp_verification_attempted` ×3 | all `VERIFIED`, `attemptNumber: 0` |
| 08:53:02 → 08:55:01 | `stage3`…`stage7_data_submitted` | data entry |
| 08:55:03 | `scan_token_issued` | real token, live tenant |
| 08:55:34 | `scan_accepted` | **real enrolment JWS RS256/JWKS-verified** |
| 08:55:34 | `registry_lookup_completed` | stub |
| 08:56:05 | `registry_review_accepted` | Stage 9 |
| 08:56:10 | `liveness_token_issued` | face session 1 |
| 08:56:20 | `liveness_attempt_terminated` | **the cancel — see STEP 4** |
| 08:58:18 | `liveness_token_issued` | face session 2 |
| 08:58:50 | `face_match_evaluated` | **liveness passed** |
| 09:00:23 | `signature_captured` | drawn, 4,460 B PNG |
| 09:01:34 | `profile_submitted` | **FRU-000000004** |
| 09:01:48 | `notification_dispatched` ×3 | submission notice, all three channels |

**Three independent OTP codes were issued, one per channel** — the CLAUDE.md rule ("a single code sent to several channels proves none of them") observed live, not merely tested. Codes were recovered from `app.otp_challenge` by recomputing `sha256(salt‖code)` over the 10⁶ space (the S5-07 method, PO-approved, local synthetic DB only) and relayed per channel.

## STEP 4 — liveness, the centrepiece

### The cancel (first hardware execution of the face-cancel path)

```
liveness_token_issued        08:56:10.374  {"faceSessionId": "3d1880f2-…d853"}
liveness_attempt_terminated  08:56:20.011  {"sdkErrorCode": "USER_CANCEL", "attemptsAfter": 1,
                                            "faceSessionId": "3d1880f2-…d853", "blockTriggered": false}
```

The SDK stack ran and tore down cleanly (logcat, device-local time = UTC+2):

```
io.uqudo.sdk.core.FaceSessionActivity
io.uqudo.sdk.face.ui.VerificationActivity      SURFACE SIZE 720x1520   (camera live)
io.uqudo.sdk.face.FacialRecognitionActivity
```
all three removed at 10:56:22.4–10:56:22.5.

| Brief's question | Hardware answer |
|---|---|
| Exact SDK code? | **`USER_CANCEL`** — the app's assumption **confirmed**, not corrected |
| Timeout arm or SDK cancel? | **SDK cancel.** Resolved in **9.64 s**; the 120 s `UqudoFaceNoResponse` arm was never entered and `APP_NO_SDK_RESPONSE` was never minted |
| Attempt spent correctly? | **Yes** — `attemptsAfter: 1`, `blockTriggered: false` (1 of 3) |
| Dead spinner (BL-054's premise for the face flow)? | **None.** 9.64 s end to end, including the PO's own interaction |
| Did the screen tell the truth about the budget? | **Yes** — see below |

**The budget question, answered precisely.** The screen showed *لم يكتمل التحقق* / *"لم نتمكن من التحقق هذه المرة. يمكنك المحاولة مرة أخرى."* with a retry button, and said **nothing** about the attempt budget. That is deliberate and correct, not an omission: [`stage10_screen.dart:72-74`](../../mobile/lib/features/liveness/stage10_screen.dart#L72-L74) — *"No attempt count is displayed, and none is counted locally. `attemptsRemaining` is on no liveness wire and this app does not invent one — R-052's named failure mode."* Decisively, the sibling branches where **no** attempt is spent *do* say so explicitly (`لم تُحتسب هذه المحاولة`, lines 346/356/364), while the `failed` branch that fired — where an attempt *was* spent — stays silent. **The app never claims "no attempt counted" when one was**, which is exactly the false-reassurance failure the brief asked about. Stage 8 can name the budget because scan attempts carry it on the wire; Stage 10 deliberately cannot. A documented divergence, not an inconsistency.

### The happy path — liveness passed

```
liveness_token_issued  08:58:18
face_match_evaluated   08:58:50.156
{"jti": "40c1b1d4-…a655", "match": true, "faceError": null,
 "matchLevel": 5, "faceSessionId": "908b24be-…70b4", "thresholdApplied": 3}
```

Real face session minted against the live tenant, **real face JWS RS256/JWKS-verified and accepted**, `match: true`, `matchLevel 5` against `thresholdApplied 3` — the same level as S1-02's baseline. Token→verified in **32 s**, well inside the 600 s session ceiling and the app's 120 s arm. Occurrence recorded; **no token or JWS content is reproduced anywhere**. The `jti` (`40c1b1d4…`) differs from the `faceSessionId` (`908b24be…`), confirming on hardware that the face JWS binds by `data.sessionId`, not `jti` — as S5-08 designed and `UqudoJwsParser.boundFaceClaims` implements.

### Signature (Stage 11)

- **Drawn** signature submitted: `{"byteSize": 4460, "contentType": "image/png", "captureMethod": "drawn"}` — *"a drawn signature submits"* proven on hardware.
- **Both input routes exercised, and their exclusivity proven in both directions on hardware** (PO: draw and upload replace each other correctly) — the [`stage11_screen.dart:129`](../../mobile/lib/features/signature/stage11_screen.dart#L129) contract *"an upload replaces anything drawn, and vice versa"*.
- The **uploaded** route's server-side downscaled byte size was **not** measured — see below.

### Submission and confirmation (Stage 12)

`profile_submitted` → **FRU-000000004**, profile status `submitted`, three `notification_dispatched` at 09:01:48 (sms, whatsapp, email — the submission notification the CLAUDE.md messaging rule requires).

The confirmation screen showed **الرقم المرجعي FRU-000000004** — identical to the audit payload — and *سيصلك القرار عبر: البريد الإلكتروني، الرسائل النصية، واتساب* (all three verified channels), while correctly stating *لم يتم اعتماد التحديث بعد* rather than claiming completion. The S5-13/S5-08 single-source constraint holds on the fresh-submit path: [`confirmation_screen.dart:23`](../../mobile/lib/features/submission/confirmation_screen.dart#L23) — *"Channel wire values, sourced from `POST /api/v1/submission/current` on every path."* Verified independently by screencap and by the PO's own photograph of the handset.

## What this run did NOT prove — stated, not faked

- **The two-person face-MISMATCH case (R-016) — NOT tested, stays owed.** Solo run; it needs a second person whose face does not match the document. Not synthesized. This is the one residual keeping the journey short of fully hardware-proven.
- **SDN_ID — unproven.** No Sudanese national ID card available. The parser's national-ID branch stays `[UNVERIFIED]`.
- **Uploaded-signature server-side byte size — not measured.** One signature per profile is enforced by a unique index ([V0046](../../backend/src/main/resources/db/migration/V0046__app_signature_artifact_one_per_profile.sql), no state filter), so the single slot went to the drawn route once the PO had drawn. The gap is narrower than it sounds: the picker, the downscale-at-capture and the replace behaviour all ran on hardware, and both routes share one long-edge rule; only the stored byte size of an uploaded capture is unmeasured. Cheap to close on any future run by uploading first.
- **Real Civil Registry / core-banking behaviour** — stubbed by design, backend-covered. Stage 9 rendered the stub's synthetic record, as expected.

## Findings triage

| # | Finding | Triage |
|---|---|---|
| 1 | Full 1→12 walk, liveness happy path, face cancel, drawn signature, submission — all pass on hardware | none — proven |
| 2 | Face-SDK cancel code is **`USER_CANCEL`**, resolved in 9.64 s, attempt spent, budget messaging truthful | none — app's assumption **confirmed** |
| 3 | **Backend jar was stale against a code commit** (caught pre-run, rebuilt) | process note — check jar mtime against the last commit touching that tier |
| 4 | `doc_front_frame` byte_size vs body — AD-004 honoured; my column misread, not a defect | none — withdrawn before filing |
| 5 | **USB link flapped repeatedly**, killing logcat and screencap mid-capture; resolved by moving adb to Wi-Fi | environment — **new, see below** |
| 6 | BL-055 recurs: **zero `I/flutter` lines** in 77,336 logcat lines | **BL-055 severity lowered** — see below |
| 7 | Uqudo SDK emits 3 non-fatal `cv::error()` OpenCV lines during document scan on armeabi-v7a | cosmetic — vendor-internal, scan succeeded |
| 8 | ICMP false negative reproduces; hotspot IP misread as stale LAN address pre-lease | environment note |

**Finding 5 — a materially better device-run technique.** The USB connection re-enumerated spontaneously (`transport_id` 1→2→3), each drop killing the logcat stream (exit 255) and truncating an in-flight screenshot to 0 bytes. S5-07 hit the same instability and worked around it by keeping the cable still. This run instead moved adb onto the hotspot — `adb tcpip 5555` then `adb connect 172.20.10.3:5555` — after which every `logcat`, `screencap` and `shell` ran over Wi-Fi, immune to USB drops, for the rest of the session. S5-07's note that "`tcpip` does not persist across unplug" remains true, but it does not need to: it survives the whole run as long as adbd is not restarted. **Recommended default for future device runs on this handset**, paired with a self-healing capture loop (`adb connect` + `logcat` in a retry loop) so no evidence is lost to a drop.

**Finding 6 — BL-055 recurs but matters less than filed.** Zero `I/flutter` lines across 77,336 captured lines, so the verbatim `PlatformException.code` still cannot be pasted from logcat on this ROM. But this run got the SDK code verbatim anyway — **`"sdkErrorCode": "USER_CANCEL"` straight out of the audit chain**, because the app forwards the code to the backend, which records it. Server-side audit is the stronger evidence in any case (it is what an operator would see). BL-055's remaining scope is narrow: SDK strings on paths that never reach the backend.

**Finding 7 — verbatim, for the record:**
```
E cv::error(): OpenCV(4.12.0) Error: Requested object was not found
(could not open directory: /data/app/com.example.mobile-…/base.apk!/lib/armeabi-v7a)
in glob_rec, file /Users/mario/…/uqudo/platform/git/opencv/modules/core/src/glob.cpp, line 279
```
Three occurrences, all at 10:55 during `EnrollmentActivity`/`ScannerActivity`, **none during the face flow**, and `scan_accepted` succeeded regardless. Inside the vendor SDK (the path is Uqudo's own build machine), not our code. Nothing to fix; recorded in case a future 32-bit issue surfaces.

## Plan updates

- **EXECUTION_PLAN.md** S5-08 🟨 → ✅ — the owed live liveness device run is done.
- **RISKS.md** R-016 — note that the first two-person test still has not happened; the residual survives this run untouched.
- **BACKLOG.md** BL-054 — face-flow evidence: no dead spinner, 9.64 s via `USER_CANCEL`, mirroring the Stage 8 result. BL-055 — recurrence plus lowered severity. New items filed for the adb-over-Wi-Fi technique and the SDK OpenCV noise.

## Gates

**No code changed this session** — no gates run, per the run brief tail. The only build artifacts produced were a rebuilt backend jar and a debug APK, neither committed.
