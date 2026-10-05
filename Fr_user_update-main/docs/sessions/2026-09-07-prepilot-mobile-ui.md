# S8-03 — Pre-pilot mobile UI, branding, typography and RTL correctness

Implements `docs/sessions/2026-09-07-research-ui-ux-design-plan.md` §0 D1–D10. Presentation,
wording and two named defects only: **no journey logic, no validation rule, no error contract and
no backend change.** Arabic copy went through a product-owner native-review gate before commit.

---

## 1. Gate output, verbatim

### mobile — `fvm flutter analyze`
```
No issues found! (ran in 17.3s)
```

### mobile — `fvm dart run tool/check_coverage.dart` (tests + 80% gate)
```
03:01 +493: All tests passed!
Line coverage: 83.40% (3562/4271 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

### backoffice — `npm run lint`
```
LINT EXIT: 0
```
Warnings only, all pre-existing `react(set-state-in-effect)` / `react(globals)` in files this diff
does not touch.

### backoffice — `npm run test:coverage`
```
 Test Files  18 passed (18)
      Tests  140 passed (140)
Statements   : 97.09% ( 501/516 )
Branches     : 86.25% ( 320/371 )
Functions    : 97.46% ( 154/158 )
Lines        : 98.9% ( 452/457 )
```

---

## 2. On-device results — **the real proof, not the gates**

Reference device: Huawei AMN-LX9, Android 9 / API 28, 2 GB. Below Impeller's API 29 threshold, so
it runs the legacy renderer and pays first-run shader compilation. Debug APK, built with
`--dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net`.

| Check | Result |
|---|---|
| **Arabic joins with the subset font** | **PASS.** «الفرنسي بياناتي», «بيانات الحساب», «تعذر تحميل قائمة الفروع…» and the whole live branch list all render as correctly connected script. This is the direct on-device test of the `--layout-features='*'` decision — the subset kept `init/medi/fina/rlig/ccmp/calt`, and had it not, every word would have broken into isolated forms. |
| **Splash** | **PASS, PO-accepted.** Emblem, «الفرنسي بياناتي», «لؤلؤة المصارف», tagline, spinner. Continuous brand blue from tap to first content — the native launch window and the Flutter screen are the same `#105097`, so there is no white flash and no visible native→Flutter handoff. |
| **Brand blue** | **PASS.** `#105097` on the launch window, the primary button, the focused field underline and its label. |
| **`textAlign: right` on the numeric islands** | **PASS.** `0000000001` renders at the RIGHT edge, directly beneath its right-aligned Arabic label «رقم الحساب». Before this session the digits sat at the left edge of the same box while the label sat at the right. |
| **Per-field `errorText`** | **PASS.** «تعذر العثور على هذا الحساب. يرجى التحقق من الرقم.» rendered attached to the account field with the error underline, not as a detached line at the bottom of the form. |
| **Page transitions** | **PASS.** Stage 1a → terminal animated vertically, direction-neutral. No wrong-way horizontal slide. |
| **Text scale 1.3×** | **PASS.** No overflow, and no `RenderFlex ... overflowed` in logcat. |
| **Baked base URL is the AWS one, not localhost** | **PASS — proven, not assumed.** See §3. |
| **Reference number (Stage 12) not misordered** | **NOT VERIFIED — environment-blocked.** See §4. |
| **National number + English name (Stage 9) not misordered** | **NOT VERIFIED — environment-blocked.** See §4. |
| **Focus advance through a multi-field form** | **NOT VERIFIED — environment-blocked.** Stage 1a has a single text field; every multi-field screen is past the blocker. |

**Frame timing, reported honestly:** logcat shows `Skipped 776 frames` at first launch and
`Skipped 67 frames` shortly after. Both are on the debug build's first run, which is where the
legacy renderer's shader compilation lands; the splash itself was accepted by the PO as smooth.
This is **not** a clean measurement of the release build's motion, and the design plan's D6(a)
records that the PO declined performance measurement on this device.

---

## 3. Live proof: the APK's baked base URL

A passing test cannot show this, and the plan is explicit that reading the build command back does
not count. Method:

1. `adb reverse tcp:8080 tcp:8080`, with a request-logging HTTP server on this machine's port 8080.
2. **Control** — `adb shell curl http://localhost:8080/...` from the device returned `HTTP 200` and
   the server logged `HIT /api/v1/reference/manifest host=localhost:8080`. The tunnel works, so a
   later absence of traffic cannot be blamed on a dead tunnel.
3. **Test** — `pm clear` then a cold start of the app. The server logged **nothing**.

An APK baked with `AppConfig`'s `defaultValue` of `http://localhost:8080` would have hit that
server. It did not. Independently, the app reached the real backend across the session: the branch
list rendered from the live reference API, and `CheckAccount` returned real coded outcomes.

---

## 4. What could not be verified, and why

**The staging environment currently has no account that can walk the journey.** Confirmed on the
handset:

- The deployed task definition `fru-staging-backend:6` sets `FRU_CORE_BANKING_CLIENT=stub`, while
  `FRU_UQUDO_CLIENT` and `FRU_CIVIL_REGISTRY_CLIENT` are both `http`. Core banking is the only
  integration still stubbed.
- `StubCoreBankingClient` is a fixed map whose sole ACTIVE account is `0000000001`.
- Entering `0000000001` returns the terminal screen «تم استكمال تحديث بيانات هذا الحساب من قبل.» —
  that profile is already COMPLETED in the staging database from earlier testing.

So Stage 1a is as far as any tester can currently get, which is why the two
`[UNVERIFIED — device check]` isolation sites remain unverified on hardware. **They are not
unverified for lack of trying, and they are not blocked by anything in this diff.** Filed as
**BL-089**, which is a one-variable flip plus a task-definition revision — the real endpoint is
already wired as a Parameter Store secret in that same revision.

This was found because the PO entered a genuine account number, got «تعذر العثور على هذا الحساب»,
and reported that the same number returns `Response_Code 1` from Postman against the real
middleware. The app, the mapping and `AccountCheckOutcome.fromResultCode` were all correct.

---

## 5. Design decisions and their reasoning

**Brand `primary` is pinned, not seeded.** `ColorScheme.fromSeed` maps its seed through a tonal
palette and returns a lighter, less saturated `primary`, so seeding with `#105097` does not give
`#105097` back. `app_theme_test.dart` asserts both that `primary` IS the brand blue and that the
seed alone would NOT have produced it — the second assertion is what stops a future
"simplification" from dropping the pin.

**The page transition was a live defect, not a preference.** Unset, Android resolves to
`PredictiveBackPageTransitionsBuilder`, which with no manifest opt-in falls back to
`FadeForwardsPageTransitionsBuilder`: a ±0.25 **horizontal** slide with LTR semantics hardcoded, and
no built-in Material transition mirrors for RTL. This Arabic app had been animating every forward
navigation in the Latin direction. `FadeUpwardsPageTransitionsBuilder`'s tween is
`Offset(0.0, 0.25)` — **x is zero**, so it is direction-neutral *by construction* rather than
merely corrected today.

**Font subsetting by Unicode range, never by scanned strings.** Customer names arrive from the
Uqudo scan and the Civil Registry and can contain characters no literal in this repo contains, so a
string-scanned subset would render a customer's own name as missing-glyph boxes.
`--layout-features='*'` is equally deliberate: pyftsubset's default feature set is not guaranteed to
retain `init/medi/fina/rlig/ccmp/calt`, and dropping those is exactly how a connected script's joins
break. Verified after subsetting (all six present, 252 Arabic code points, 196 + 140 presentation
forms) and then verified again on the device.

**Two isolation mechanisms, deliberately not interchangeable.** A value standing alone in its own
widget gets `textDirection: ltr` — a `Text` with its own direction is already its own bidi
paragraph, which is the strongest isolation available. FSI/PDI (U+2068/U+2069) is used **only** where
a Latin value is interpolated into an Arabic run sharing one `Text`. After the copy pass that is one
site: the picked filename at `salary_certificate_field.dart` — a site the design plan's own list did
not contain, found by scanning for Latin inside Arabic. The confirmation screen's reference number
deliberately does **not** use FSI/PDI: it is `SelectableText` so the customer can copy and quote it
at a branch, and format characters would travel into whatever they paste. Asserted directly in
`ltr_value_test.dart`.

**Letter-by-letter reveal was rejected on technical grounds.** The PO asked for the slogan to appear
letter by letter. Arabic is a connected script: a growing substring re-shapes the whole word on
every frame, so letters already on screen visibly change form as the next arrives. The implemented
alternative renders the finished, correctly shaped text and sweeps a **right-to-left** gradient mask
across it — same effect, shaping never disturbed.

**The splash animation is decoration and never a gate.** D7.3 forbids a minimum display time. The
controller is fire-and-forget: `_navigate` runs the instant the decision resolves. A test asserts
navigation completes ~120 ms into an 1800 ms timeline, which is the property that keeps D7.3 true.

**`ArabicNounAgreement` zero fix — no revert-restore proof, and why.** CLAUDE.md's rule: a direct
wrong-value assertion needs no revert because it cannot pass against the bug. `phrase(0)` is
asserted to equal `'0 طفلاً'` and asserted *not* to equal `'0 أطفال'`; against the old
`if (n <= 10)` branch those fail by the assertion itself, not by ordering or an indirect effect.

---

## 6. Review findings and dispositions

`@agent-reviewer` ran twice: a **pre-build baseline audit** of the plan's claims against source, and
a **post-build review** of the diff.

### Baseline audit — three spec errors found before a line was written
| Finding | Disposition |
|---|---|
| The plan's "seven display sites" was wrong; it means seven *total* (5 inputs + 2 displays), and §4.3 contradicts D4.4 | **Fixed.** Settled as **eight**: 5 inputs + 3 displays. |
| `stage9_screen.dart:405` («الاسم بالإنجليزية») — an all-Latin value through a bare helper, absent from the plan's site list | **Fixed.** Isolated; it was the most likely real misorder site. |
| The splash edit would have been a no-op: with `minSdk 24`, `drawable-v21/` is what applies, not the `drawable/` the plan named | **Fixed.** Both kept in sync. |
| A second ISO-date site at `ProfileListPage.tsx` the plan did not name | **Fixed.** |
| Wording churn estimated at 106 assertions; real total 257 | Noted. **Actual churn was 7** — most assertions target strings D8 never touched. |

### Post-build review
| Finding | Disposition |
|---|---|
| **Android 12+ dark mode would lose the brand splash** — `values-night` outranks `values-v31` in Android's qualifier precedence, so `LaunchTheme` resolved to the variant Android 12+ ignores, giving a white flash | **Fixed**, using the reviewer's own preferred remedy: `values-night/` deleted entirely, so no qualifier outranks `v31`. It was byte-identical to `values/` anyway. |
| Deleting the demo test removed the **only** test that pumped `FruApp` and asserted the app-wide forced-RTL wrapper; the coverage gate cannot see this, per CLAUDE.md's own caveat | **Fixed.** New `test/core/app_test.dart` asserts RTL, the wired brand theme, the direction-neutral transition and the `ar` locale. |
| Per-field `errorText` (plan §4) was silently skipped | **Built**, on PO instruction, via a shared `FieldErrorState` mixin. |
| `generate_brand_assets.py` docstring claimed Pillow only; it also imports numpy | **Fixed.** |
| `OFL.txt` is on disk but not declared as a Flutter asset | **Left deliberately.** The licence travels with the source; nothing in-app displays it. |
| `AdminHomePage.tsx:36` still carries the «الواجهة الخلفية» calque D8.5 removed from the header | **NOT changed.** It is new copy in a different sentence, and the PO is the wording gate. Filed for the next copy pass. |
| `demoInitProvider` and `ArabicNounAgreement.children` are now production-dead | **Kept, both documented as such in place.** `children` is correct, tested domain knowledge; `demoInitProvider` is the one worked example of the activate-then-pin sequence the real session-boundary hook must perform. |

---

## 7. The wording gate

D8's strings were PO-reviewed on 2026-09-07 before this session. The gate here was a confirmation
pass over what was actually applied, plus the strings this session authored new. **Two items were
flagged as outside the reviewed set and both were explicitly ruled on:**

- **A 14th «الخادم» site.** D8.2 listed 13; `stage8_screen.dart` carried a fourteenth as a fallback
  clause inside a larger sentence. PO: apply the short form «تعذر الاتصال.». Applied. There is now
  **no «الخادم» anywhere in the app.**
- **The Stage 12 sentence split.** The approved sentence now spans two visual blocks — body text
  plus a tinted warning box holding «بعد الإرسال لا يمكن التعديل من التطبيق.» above the submit
  button. Same words, nothing added. PO: keep the split.

**Splash tagline changed by PO decision:** «تحديث بيانات حسابك» → **«خطوات بسيطة لتحديث بياناتك»**.
Per D10.4 it promises nothing the bank has not committed to — no time claim, no outcome claim — and
is gender-safe in writing.

Commit sequencing honoured the gate: `7f87544` contained **zero** Arabic string changes.

---

## 8. Bank identity

Correct throughout: app «الفرنسي بياناتي», launcher «بياناتي», bank «البنك السوداني الفرنسي», brand
line «لؤلؤة المصارف». `git diff 00867af | grep "بنك السودان"` returns nothing.

The repo was already clean before this session — all three backend renderers carry the formal name
(fixed at S8-02, BL-085) and mobile named the bank nowhere. The strings added here are byte-identical
to the backend's, so **there is no disagreement between message text and UI text**. The one surviving
«بنك السودان» literal in the repo is a rendered SMS body quoted as evidence in the S3-05 session
report; it is an immutable record and was deliberately left alone.

No Latin transliteration reaches any user-facing string.

---

## 9. Scope kept out, and filed

| | |
|---|---|
| **BL-086** | WhatsApp row disable. `_whatsappSelected` flows to the backend and determines which OTP challenges are created, so a UI-only disable would be a behaviour change wearing a presentation costume — and an incomplete one, since draft-restore would re-enable it. |
| **BL-087** | Stage 12 "check your answers". No honest data source: the pointer carries no field data, `DataEntryApi` is write-only, and the local draft has a queue-and-flush path that can hold values the backend never received. Stage 12 was enriched **visually only**. |
| **BL-088** | iOS `Info.plist` still reads `Mobile` — a Latin customer-facing string. **NOT DONE, and must not be assumed done** because Android was. Also records the three-identity-token point (`SFB` sender / formal name in body / «بياناتي» launcher) for the pilot brief. |
| **BL-089** | Staging runs the core-banking stub, and its one account is already terminal. Blocks staff testing outright. |

---

## 10. iOS and packages

**No new Flutter or pub package was added by this session.** The font is a bundled asset, the icons
are generated by a committed script, and `flutter_native_splash` was refused by D7.4. CLAUDE.md's
per-package iOS-support check therefore has nothing to report — stated plainly rather than implied
as performed. iOS was explicitly out of scope and remains unbuilt here (BL-088).

---

## 11. Files and commits

Generated artefacts are produced by committed scripts and must never be hand-edited:
`mobile/tool/fetch_and_subset_fonts.py` (fonts) and `mobile/tool/generate_brand_assets.py` (icons,
splash emblem).

Four commits, sequenced so the wording gate is visible in the history rather than asserted:

```
c3970c6 docs: S8-03 session report, plan files, and four new backlog items
23231d7 feat(mobile): splash motion, per-field errors, and the review fixes
617a052 feat(mobile,backoffice): Arabic copy pass — PO native review complete
7f87544 feat(mobile): brand identity, Arabic typography and RTL correctness fixes
```

```
To https://github.com/Osmantou/Fr_user_update
   00867af..c3970c6  main -> main
```

Pushed straight to `main`, per CLAUDE.md. `7f87544` carries **zero** Arabic string changes — that
is what let the visual work land while the copy waited on the PO.

**One untracked file was deliberately not committed:** `strartup.mp4`, a screen recording the PO
dropped in the repo root. It is not project content. `git add -A` was avoided for exactly this
reason and every commit staged explicit paths.
