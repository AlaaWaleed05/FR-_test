# AD-001 — Whole-Stack Decision: Mobile + Backend + Back Office

**Date:** 2026-08-19 · **Researcher:** research agent · **Status:** recommendation, not yet ratified

## Reading of the question

The task is unambiguous on scope but ambiguous on one point: whether "mobile app" is fixed as a *native-installed* app. PROJECT_PLAN.md line 14 says "Platforms: mobile iOS + Android (assumed, to confirm)". I investigated the most defensible reading — **an installed iOS + Android app is the target** — but because the gating research turned up decisive evidence about a browser-delivered alternative, I carry a PWA option through as a named flip condition rather than silently dropping it. Readings not pursued: (a) mobile-web-only as the primary customer tier; (b) a bank-branch/tablet-assisted journey rather than self-service.

---

## 1. GATING SUB-QUESTION — Uqudo's current SDK matrix

**Answer: Uqudo ships first-party, currently-maintained SDKs for native Android, native iOS, Flutter, React Native, Capacitor, Cordova and Web. There is a first-party Flutter plugin. There is a first-party React Native module. There is a browser/web SDK, and it is not a toy — the FIB reference bank shipped its Sudan KYC journey on it. The .NET binding is documented but not distributed on NuGet and should be treated as unavailable.**

### The matrix

| Platform | Package / coordinate | Current version | Released | Evidence |
|---|---|---|---|---|
| Android (native) | `io.uqudo.sdk:Uqudo` from `https://rm.dev.uqudo.io/repository/uqudo-public/` | **3.10.0** | — | [DOC] [Android · Prepare Environment](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/prepare-environment.md) |
| iOS (native) | pod `UqudoSDK` (+ pinned `OpenSSL-Universal` 3.3.3001); SPM also offered | **3.10.0** | — | [DOC] [iOS · Prepare Environment](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/ios/prepare-environment.md) |
| **Flutter** | `uqudosdk_flutter` (pub.dev) | **3.10.0** | 2026-08-10 | [OBSERVED] `C:\Users\DELL\AppData\Local\Pub\Cache\hosted\pub.dev\.cache\uqudosdk_flutter-versions.json`; [DOC] [pub.dev changelog](https://pub.dev/packages/uqudosdk_flutter/changelog) |
| **React Native** | `uqudosdk-react-native` (npm) | **3.10.0** | 2026-08-10 | [OBSERVED] npm registry search API, `https://registry.npmjs.org/-/v1/search?text=uqudo` |
| Capacitor | `uqudosdk-capacitor` (npm) | **3.10.0** | 2026-08-10 | [OBSERVED] same |
| Cordova | `uqudosdk-cordova` (npm) | **3.10.0** | 2026-08-10 | [OBSERVED] same |
| **Web (browser)** | `uqudosdk-web` (npm) | **4.1.3** | 2026-06-24 | [OBSERVED] same; [DOC] [Web SDK changelog](https://docs.uqudo.com/docs/kyc/uqudo-sdk/changelog/web-sdk.md) |
| .NET | doc page exists, points to a GitHub sample only | **no NuGet package** | — | [OBSERVED] NuGet search API returned `totalHits: 0` for `uqudo`, `uqudosdk`, `UqudoSDK.Maui` |

**Feature coverage of the three things we need** — token issuance, document scan + validation, document detail retrieval (image + portrait), liveness/biometric — is present on Android, iOS, Flutter, React Native, Capacitor, Cordova and Web. Each has both an **Enrolment Flow** (document scan + facial recognition) and a **Face Session Flow** (standalone liveness). [DOC] [docs.uqudo.com/llms.txt](https://docs.uqudo.com/llms.txt) — the index lists `enrolment-flow` and `face-session-flow` under `integration/android`, `integration/ios` and `integration/web`.

**Machine-readable docs — record this for future sessions.** Uqudo publishes an llms.txt index at **`https://docs.uqudo.com/llms.txt`**, and every documentation page has a markdown variant reachable by appending `.md` to the URL. [DOC] Use these rather than scraping the rendered site. Caveat [OBSERVED]: several `.md` pages (`integration/flutter.md`, `integration/react-native.md`, `integration/dotnet.md`, `integration/web.md`) contain only a pointer to a GitHub sample app and no technical content — the substance lives on the Android/iOS/Web sub-pages.

### Version cadence — Uqudo is actively maintained, not coasting

[OBSERVED] from `uqudosdk_flutter-versions.json` in the local pub cache, last eleven releases:

`3.4.1+1` 2025-05-07 · `3.4.2` 2025-06-04 · `3.5.0` 2025-07-28 · `3.6.0` 2025-09-08 · `3.6.1` 2025-10-24 · `3.6.2` 2026-01-19 · `3.7.0` 2026-02-13 · `3.8.0` 2026-04-03 · `3.8.0+1` 2026-04-10 · `3.9.0` 2026-06-23 · `3.10.0` 2026-08-10.

That is a release roughly every 6–10 weeks, sustained over 15 months, with the latest nine days old. Flutter, React Native, Capacitor and Cordova all shipped 3.10.0 **on the same day**, which means Uqudo releases its wrappers in lockstep rather than letting one lag. [OBSERVED] npm publish dates vs pub.dev publish date, both 2026-08-10.

### Detail that matters and is easy to get wrong

- **The result is a JWS, not JSON.** `enroll()` and `faceSession()` return a JWS compact-serialization string; Uqudo's guidance is that parsing and signature validation happen **server-side only**. [DOC] [Validation and Parsing](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/validation-and-parsing). This is confirmed by the plugin's own native code and is framework-agnostic — it applies equally to Flutter, RN, Capacitor and Web.
- **Images are IDs, not base64.** `frontImageId`, `backImageId`, `faceImageId`, `auditTrailImageId` are references; the actual bytes come from a separate authenticated download. [DOC] [Scan Object](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/scan-object), corroborated [OBSERVED] by `UqudoServiceImpl.getImageById()` calling `GET {imageUrl}/info/img/{imgId}` with a bearer token — `C:\Users\DELL\Documents\Osman\Waleed\FIB\backend server fib\utility\src\main\java\com\aztech\utility\service\Impl\UqudoServiceImpl.java` lines 76–108.
- **Sudan document types are in the SDK.** `SDN_ID`, `SDN_DL`, `SDN_VL` are present in the Flutter plugin's `DocumentType` enum. [OBSERVED] `C:\Users\DELL\AppData\Local\Pub\Cache\hosted\pub.dev\uqudosdk_flutter-3.8.0+1\lib\uqudosdk_flutter.dart` lines 28–30. FIB uses `DocumentType.SDN_ID` for national ID and `PASSPORT` for passports. [OBSERVED] `C:\Users\DELL\Documents\Osman\Waleed\FIB\mobile\lib\core\uqudo\uqudo_service.dart` lines 17–26.
- **The Web SDK is proven for Sudan.** The FIB web bundle imports `uqudosdk-web` and contains the literals `SDN_ID`, `SDN_DL`, `PASSPORT`, plus a lazy-loaded `LivenessCheckJourny` chunk. [OBSERVED] `C:\Users\DELL\Documents\Osman\Waleed\FIB\Web_Code\assets\OnboardingJourny-B-T80Kxx.js` and `LivenessCheckJourny-B1eowIWD.js`. This is the single most decision-relevant thing I found that the task brief did not anticipate.

### What the gate eliminates

- **.NET MAUI — dropped.** A documentation page and a GitHub sample exist, but nothing is published to NuGet under any Uqudo-related name. [OBSERVED] three separate NuGet search queries returned zero hits. Consuming a binding library by cloning a sample repo and building AAR/framework bindings by hand is not a maintenance posture a solo developer should accept for a bank's identity pipeline. Dropped regardless of C#'s other merits.
- **Kotlin Multiplatform / Compose Multiplatform — dropped.** No Uqudo SDK. You would write and maintain two `expect/actual` bridges to the native SDKs yourself.
- **Cordova — dropped on maintenance grounds, not availability.** The plugin is current (3.10.0), but Cordova as a platform is in maintenance and choosing it in 2026 for a greenfield bank app buys nothing Capacitor does not.

### What survives the gate

Flutter · React Native · Capacitor/Ionic · native Android + native iOS · Web SDK (PWA).

Native-twice is dropped separately: for one developer, two native codebases doubles the most expensive tier and buys nothing here, because the Uqudo SDK supplies its own full-screen UI in every case.

---

## 2. ANSWER — the recommendation

**Mobile: Flutter (current stable 3.47.x), `uqudosdk_flutter` 3.10.0.
Backend: Spring Boot 4.1.x on Java 21 LTS, Oracle `ojdbc` from Maven Central, calling `ProcessOmniCheckAct` via Spring's `SimpleJdbcCall`.
Back office: React 19 + TypeScript + Vite, with a component library chosen for first-class RTL (Ant Design `ConfigProvider direction="rtl"` is the lowest-friction option; MUI + `@mui/stylis-plugin-rtl` is the FIB-precedent option).**

**Minimum supported versions:** Android **API 24** (Android 7.0), compileSdk/targetSdk **36**; iOS **15.0**, built with Xcode 26 / iOS 26 SDK. Back office: evergreen Chrome/Edge/Firefox/Safari, last two versions.

This is the FIB stack on two of three tiers, but **not for the reason it looks like.** Code reuse from FIB is worth far less than the brief implies — I quantify that in §5. The case rests on three things that are true under *our* constraints: Flutter removes RTL as an ongoing cost line rather than managing it; Spring Boot over Oracle's own JDBC driver is the only candidate path to `ProcessOmniCheckAct` with no native-client deployment dependency and no minimum Oracle server version above 11.2; and the Flutter plugin is the one wrapper we can read, have already read, and have a working (if buggy) reference integration for against Sudan documents.

The cost is honest and I am not going to hide it: **three languages for one developer.** Option B (TypeScript everywhere) is a genuinely serious competitor and I nearly recommended it. §7 states exactly what would flip it.

---

## 3. Evidence

### 3.1 Uqudo SDK platform floors — these are *lower* than the frameworks'

| | Uqudo floor | Source |
|---|---|---|
| Android `minSdkVersion` | **23** | [OBSERVED] plugin `android/build.gradle` line 38: `minSdkVersion 23`; corroborated [OBSERVED] CHANGELOG 3.8.0: *"this version requires a minimum SDK level of 23, so starting from this release your application must set `minSdkVersion` to 23 in order to build successfully"* |
| Android `compileSdk` | **35** (as of 3.6.0) | [OBSERVED] CHANGELOG 3.6.0: *"You need to update compileSdk Version to 35 in order to build with this version of the SDK."* |
| Android `targetSdk` | 34 mandatory since 3.0.0 | [OBSERVED] CHANGELOG 3.0.0: *"For Android application development, targeting SDK 34 is now mandatory."* |
| Android ABIs | **armeabi-v7a + arm64-v8a only** | [OBSERVED] CHANGELOG 2.5.0: x86_64 was added so the *app* builds and runs on an emulator, but *"the SDK part, armeabi-v7a and arm64-v8a remain the only supported architectures"* |
| Java/Kotlin | Java 17 source/target | [DOC] Android Prepare Environment |
| iOS deployment target | **12.0** general; **13.0+** for NFC | [OBSERVED] podspec line 15 `s.platform = :ios, '12.0'`; [DOC] iOS Prepare Environment |
| iOS pinned transitive | `OpenSSL-Universal` **exactly** `3.3.3001` | [OBSERVED] podspec line 14; [OBSERVED] CHANGELOG 3.5.0: *"Using version 1.x will cause the SDK to crash."* |

**Consequence for us:** the Uqudo floor never binds. Every framework's own floor is higher. So minimum-version selection is a framework-and-store question, not an SDK question.

**Consequence for testing:** the x86_64 note means **the Uqudo scan/liveness flow cannot be exercised on a standard x86_64 Android emulator.** A physical arm64 Android device and a physical iPhone are non-negotiable line items for whichever option we pick. This cost is identical across Flutter, RN, Capacitor and native, and is *absent* only from the Web SDK path.

### 3.2 Framework floors

| | Android | iOS | Source |
|---|---|---|---|
| **Flutter 3.47 (current stable)** | **API 24** min (API 23 and earlier unsupported) | **iOS 15** min (iOS 14 and earlier unsupported) | [DOC] [Supported platforms](https://docs.flutter.dev/reference/supported-platforms) |
| **React Native 0.87 (current)** | `minCompileSdk` 34 for libraries; compileSdk/buildTools 37; Kotlin 2.0+; Node ≥ 22.13.0 | not stated in the 0.87 notes | [DOC] [RN 0.87 release post](https://reactnative.dev/blog/2026/08/11/react-native-0.87) |
| React Native — last confirmed iOS bump | — | **15.1**, announced at 0.76 | [DOC] [RN discussions-and-proposals #812](https://github.com/react-native-community/discussions-and-proposals/discussions/812) |
| React Native `minSdkVersion` for 0.87 | **[UNVERIFIED]** — not stated in the release notes I could retrieve | — | — |

### 3.3 Store requirements — these bind harder than either

- **Google Play, effective 2026-08-31 (twelve days from now):** new apps and app updates must target **API 36**. Extension available to 2026-11-01 via Play Console. [DOC] [Target API level requirements](https://developer.android.com/google/play/requirements/target-sdk)
- **Apple, effective 2026-04-28 (already in force):** uploads to App Store Connect must be built with **Xcode 26 or later** using the **iOS 26 SDK**. [DOC] [Apple upcoming requirements](https://developer.apple.com/news/upcoming-requirements/)

**⚠️ Risk that applies to every native option equally.** Uqudo's changelog states compileSdk 35 as of 3.6.0 and says nothing about 36 or 37 through 3.10.0. [OBSERVED] `CHANGELOG.md`, all entries. I could not find a statement that the Uqudo Android SDK is validated at `targetSdk 36`. Whether it is compatible is **NOT DETERMINED** and must be confirmed with Uqudo support before Sprint 1 planning locks. Positive signal, not proof: 3.6.0 added **16 KB page-size alignment** [OBSERVED CHANGELOG 3.6.0], which is itself a Play requirement for the Android 15+ era, so Uqudo is tracking Play policy rather than ignoring it.

### 3.4 RTL and Arabic — assessed per option, not as an i18n checkbox

**Flutter — strongest.** RTL is a property of the widget tree, not of the app process. `GlobalWidgetsLocalizations.delegate` sets `Directionality` from the locale; `EdgeInsetsDirectional`, `AlignmentDirectional`, `start`/`end` are first-class; Material widgets mirror themselves. **No restart is required to switch direction** — changing `MaterialApp.locale` rebuilds the tree with the new direction immediately. [DOC] [Flutter internationalization](https://docs.flutter.dev/ui/accessibility-and-internationalization/internationalization). Arabic shaping is done by Flutter's own engine (HarfBuzz), so it is identical on both platforms rather than platform-dependent.
[OBSERVED] FIB exercises exactly this: `main.dart` lines 52–59 wire `locale`/`localizationsDelegates`/`supportedLocales`, and `locale_provider.dart` flips `Locale('ar')` at runtime from a button in `email_and_phone_screen.dart` lines 158–174. Note that FIB defaults to `Locale('en')` (`locale_provider.dart` line 9) — FIB is *English-first with an Arabic toggle*, which is **not** our requirement. We are Arabic-first. That is a one-line default change, not a structural one.

**React Native — materially weaker, and the docs say so.** RTL is a **global, process-wide flag** (`I18nManager`), not a tree property. `I18nManager.forceRTL()` **requires an app reload to take effect**. Enabling RTL at all needs native edits on both platforms (`RCTI18nUtil allowRTL:YES` in AppDelegate; `I18nUtil.allowRTL` plus `android:supportsRtl="true"`). Directional icons must be flipped manually via `I18nManager.isRTL`, and gestures and animations must have their deltas negated by hand. [DOC] [RTL support for React Native apps](https://reactnative.dev/blog/2016/08/19/right-to-left-support-for-react-native-apps). For an Arabic-first app this is a permanent discipline tax on every screen, and it is the single biggest reason RN is not my recommendation.

**Capacitor/Ionic (webview) — very strong, arguably equal to Flutter.** RTL is `dir="rtl"` on `<html>` plus CSS logical properties, which is the mature, browser-native solution; Arabic shaping is the system webview's, which is excellent on both platforms. This is a genuine point in Capacitor's favour that a naive comparison would miss.

**Back office (React) — solved either way, with different bills.**
- Ant Design: `ConfigProvider` has a `direction` prop taking `ltr | rtl`, plus `locale` from `antd/locale`. Built in, no build-time CSS transform, MIT, and its `Table` is fully featured for free — which is exactly what "navigate the profile database" needs. [DOC] [antd ConfigProvider](https://ant.design/components/config-provider)
- MUI: needs `createTheme({ direction: 'rtl' })`, `dir="rtl"` on the root, **and** `npm install stylis @mui/stylis-plugin-rtl` wired through an emotion `CacheProvider`. Documented caveat: **portal components such as `Dialog` do not inherit `dir` from parents** and must be given `dir="rtl"` individually. [DOC] [MUI right-to-left](https://mui.com/material-ui/customization/right-to-left/) That caveat is a recurring bug source in Arabic MUI apps.
- [OBSERVED] FIB's web app took the MUI route: the bundle directory contains `@mui-*.js`, `@emotion-*.js` and a **`cssjanus-*.js`** chunk (cssjanus is the CSS-flipping engine behind `stylis-plugin-rtl`), and the main bundle contains the literal `"rtl"`. Arabic strings are present in the journey chunks. `C:\Users\DELL\Documents\Osman\Waleed\FIB\Web_Code\assets\`. So RTL React + MUI is proven to work for an Arabic Sudan banking journey — it just costs more setup than antd.

### 3.5 Oracle connectivity per candidate backend runtime

The requirement is calling a stored procedure with an OUT parameter (`ProcessOmniCheckAct`: branch + account → 1/2/-1) from a runtime a solo developer can deploy.

| Runtime | Driver | First-party? | Current | Licence | Minimum Oracle **server** version |
|---|---|---|---|---|---|
| **Java / Spring Boot** | `com.oracle.database.jdbc:ojdbc17` (also `ojdbc11`, `ojdbc8`) | **Yes, Oracle** | **23.26.3.0.0**, ~late Jul 2026 | Oracle Free Use Terms and Conditions (FUTC) | ojdbc8 line reaches back to older servers; FIB uses `ojdbc8` [OBSERVED] |
| **Node / NestJS** | `node-oracledb` | **Yes, Oracle** | actively maintained | Apache-2.0 | **Thin mode: DB 12.1+.** Thick mode reaches DB 11.2 but **requires Oracle Instant Client installed on the host** |
| **.NET / ASP.NET Core** | `Oracle.ManagedDataAccess.Core` | **Yes, Oracle** | **2.19.320**, 2026-07-23 | Oracle licence, `requireLicenseAcceptance: false` | fully managed, no native client |
| **Dart** (for a Dart-everywhere stack) | `oracledb` (3 likes), `oraffi` (1 like), `dart_odbc` (28 likes) | **No — no Oracle-maintained driver exists** | — | community | — |

Sources: [OBSERVED] Maven Central search API for `g:com.oracle.database.jdbc`; [OBSERVED] Sonatype `gav` query for `ojdbc17`; [DOC] FUTC licence per Maven Central artifact metadata, [oracle-free-license](https://www.oracle.com/downloads/licenses/oracle-free-license.html); [DOC] [node-oracledb introduction](https://node-oracledb.readthedocs.io/en/latest/user_guide/introduction.html) — *"Thin mode: Oracle Database 12.1 or later"*, *"Thick mode: Oracle Database 11.2 or later"* with Oracle Client 19+; [OBSERVED] NuGet registration API for `oracle.manageddataaccess.core`; [OBSERVED] pub.dev search for `oracle`.

**Three consequences.**
1. **Dart-everywhere is dead.** No Oracle-maintained Dart driver; the community options have single-digit likes. Not acceptable for a bank core-banking call. Named and dropped.
2. **Node is viable but carries an unresolved dependency on OQ-002.** If the bank's core runs Oracle 11g — entirely plausible for a Sudan core-banking install — node-oracledb thin mode will not connect and we must install and version-manage Oracle Instant Client on the backend host. We cannot check this today. Java has no equivalent cliff.
3. **FIB gives us no precedent here at all.** [OBSERVED] I grepped the entire FIB backend for `ProcessOmniCheckAct`, `StoredProcedure`, `CallableStatement`, `@Procedure`, `{call` — **zero matches**. FIB uses `ojdbc8` for its *own* Oracle persistence via plain Spring Data JPA (`CustomerAccountRepository` is a bare `JpaRepository`) and reaches the bank over **HTTP** via `ApiClientService`. Our stored-procedure requirement is new work under every option. Anyone who assumes "FIB already does the Oracle bit" is wrong.

### 3.6 Backend runtime maturity

- **Spring Boot 4.1.0** is current GA; requires **Java 17 minimum**, supported to Java 26; Maven 3.6.3+; Gradle 8.14+/9.x. [DOC] [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) FIB is on Spring Boot 3.4.0 / Java 17 [OBSERVED `pom.xml` lines 8, 30] — one major behind; we start clean on 4.1.
- **NestJS `@nestjs/core` 11.2.1**, MIT, Node ≥ 20. [OBSERVED] npm registry.
- **React Native support window is the harshest of any component in this decision.** The release working group maintains **the latest version plus the previous two minors**; older minors move to "Unsupported — no new releases are to be expected." [DOC] [react-native-releases support policy](https://github.com/reactwg/react-native-releases/blob/main/docs/support.md) At RN's cadence that is roughly a nine-month window before a solo developer is on an unsupported base. RN 0.87 alone removed `InteractionManager`, removed `NativeMethods`/`NativeMethodsMixin` types, removed the `Touchable` root export, made the Strict TypeScript API default (breaking deep imports, with a temporary opt-out only *through 0.88*), and removed the `useTurboModules` flag. [DOC] RN 0.87 release post. For a solo developer maintaining a bank app for years, this is the load-bearing objection to Option B — larger, in my judgement, than the language-count saving is a benefit. [UNVERIFIED — this is a judgement about future maintenance load, not a measured fact.]

### 3.7 Release-build behaviour

- **Android + Uqudo:** the SDK ships obfuscated; the docs require the ProGuard/R8 configuration (`proguard-android-optimize.txt` plus `proguard-rules.pro`) in release builds with minification enabled, and recommend ABI splits with `universalApk` disabled, or an AAB. [DOC] Android Prepare Environment. Combined with §3.1's arm-only ABI list, a universal APK would ship dead x86 weight and still not work on an emulator.
- **Gradle repository injection.** The Flutter plugin injects Uqudo's Maven repo via `rootProject.allprojects { repositories { ... } }`. [OBSERVED] plugin `android/build.gradle` lines 17–22. This is incompatible with `dependencyResolutionManagement { repositoriesMode = FAIL_ON_PROJECT_REPOS }`. [OBSERVED] FIB's `android/settings.gradle.kts` declares no `dependencyResolutionManagement` block, so the injection works — but if we adopt a newer template that does, we will hit this. Note it; it is a ten-minute fix once you know, and a lost afternoon if you don't.
- **iOS + Uqudo:** `OpenSSL-Universal` is pinned to an exact version, so any other pod wanting a different OpenSSL will conflict. arm64 required. [DOC]/[OBSERVED] as above.
- **⚠️ FIB proves none of this.** [OBSERVED] `C:\Users\DELL\Documents\Osman\Waleed\FIB\mobile\android\app\build.gradle.kts` lines 33–39: the release build type has **no `isMinifyEnabled`, no ProGuard configuration, and is signed with the debug key** with a `TODO` beside it. [OBSERVED] there is **no `ios/Podfile.lock` and no `ios/Pods` directory** in the FIB clone, while `android/.gradle/8.14/` exists with populated caches — meaning the Android build has been run and **iOS has never been built**. The FIB reference therefore validates a *debug Android* path only. It is evidence that Flutter+Uqudo works; it is **not** evidence that a signed, minified, store-ready build works on either platform, and it says nothing about iOS parity.

### 3.8 Licensing

- Uqudo requires a commercial licence regardless of platform: *"In order to use the SDK you require a valid license."* [OBSERVED] plugin `README.md` line 20. The npm wrapper declaring `MIT` [OBSERVED registry] covers the wrapper only, not the native binaries. This is identical across all options and therefore not a differentiator — but it is an AD-002/commercial dependency: **we need a Uqudo tenant and credentials for Sudan document types before Sprint 1 can be tested end to end.**
- Oracle JDBC on Maven Central: FUTC, free for use with Oracle Database. Not a blocker.
- Flutter (BSD-3), React (MIT), NestJS (MIT), Spring Boot (Apache-2.0), Ant Design (MIT), MUI core (MIT). No blockers.
- **One trap:** if we choose MUI for the back office, MUI **X** DataGrid's Pro/Premium tiers are commercial. A back office that "navigates the profile database" will want column filtering, sorting and pagination — all in the free tier — but if requirements creep to grouping/aggregation/export, MUI X becomes a licence purchase. Ant Design's `Table` has no such cliff. One line, flagged; not resolved here.

### 3.9 What FIB is and is not

[OBSERVED] `../FIB/Web_Code` is **not** a back office. The chunk names are `OnboardingJourny`, `LivenessCheckJourny`, `OTP`, `PersonalInfo`, `Identity`, `IdentityVerification`, `Residency`, `Signeture`, `Currency`, `Done`, `ErrorPage`. It is the **customer onboarding journey rendered in a browser**, using `uqudosdk-web`, with Sudan document types and Arabic RTL. The directory also contains two generations of build output side by side — a `static/js/*.chunk.js` CRA-style tree and an `assets/*-<hash>.js` Vite tree. [UNVERIFIED — recovered from bundle and source-map filenames, no source tree exists to confirm.]

So: **FIB contributes zero back-office precedent, and contributes strong evidence for a browser-based customer journey.** Both of those cut against the reflexive "copy the FIB stack" answer, which is why I am stating the case for Flutter on its own merits below rather than on inheritance.

---

## 4. The four whole-stack options

### Option A — Flutter + Spring Boot (Java) + React/TypeScript ← **RECOMMENDED**

| | |
|---|---|
| **Mobile** | Flutter 3.47.x, `uqudosdk_flutter` 3.10.0, Riverpod + go_router + dio |
| **Backend** | Spring Boot 4.1.x, Java 21 LTS, `ojdbc` (version chosen once the core's Oracle version is known), `SimpleJdbcCall` for `ProcessOmniCheckAct`, Nimbus JOSE+JWT for JWS verification against `https://id.uqudo.io/api/.well-known/jwks.json` |
| **Back office** | React 19 + TypeScript + Vite + Ant Design (`ConfigProvider direction="rtl"`) |
| **Languages** | 3 — Dart, Java, TypeScript |
| **Min versions** | Android API 24 / compileSdk 36 / targetSdk 36; iOS 15.0, Xcode 26 |

**What it costs us.** Three toolchains for one person: Flutter+Gradle+Xcode, Maven+JVM, npm+Vite. Three dependency-update rhythms. Context-switching cost on every cross-tier change — a new field on a profile touches Dart, Java and TypeScript. That is the real bill and it is paid every sprint.

**RTL/Arabic:** best available. Framework-level, no restart, no CSS transform pipeline, engine-level Arabic shaping. Back office needs the antd/MUI RTL setup once.
**Uqudo:** first-party plugin, current, lockstep releases; the only option with a locally readable reference integration exercising `SDN_ID`.
**Oracle:** best available. Oracle's own driver, no native client, no minimum server version cliff, `SimpleJdbcCall` handles OUT parameters idiomatically.
**Release build:** the R8/ABI/OpenSSL constraints of §3.7 apply and are unproven by FIB — budget real time for the first signed build on each platform.
**Parity:** high in principle; **unproven on iOS in the reference** — treat first iOS build as a genuine spike, not a formality.
**Exit cost if wrong:** highest of the four. The mobile tier is a full rewrite. The backend and back office survive any mobile pivot untouched, and the Uqudo backend work (token, JWS verification, image download) survives *completely* because it is plain HTTPS. So the exit cost is bounded to one tier — roughly the mobile tier's whole budget.

### Option B — React Native + NestJS + React/TypeScript

| | |
|---|---|
| **Mobile** | React Native 0.87 (Expo prebuild / development build), `uqudosdk-react-native` 3.10.0 |
| **Backend** | NestJS 11, Node ≥ 22, `node-oracledb` |
| **Back office** | React 19 + TypeScript + Vite + Ant Design |
| **Languages** | **1 — TypeScript** |
| **Min versions** | Android: API level [UNVERIFIED for 0.87]; compileSdk 37, targetSdk 36. iOS 15.1 [UNVERIFIED for 0.87; confirmed at 0.76] |

**What it costs us.** The one-language saving is real and is the strongest argument any option has. Shared DTO types between backend and both clients, one package manager, one test runner, one linter, one mental model.

Against it, under our constraints specifically:
- **RTL is a process-wide flag requiring a reload, with manual icon/gesture/animation flipping.** [DOC §3.4] For an Arabic-first product this is not a footnote; it is a per-screen discipline for the life of the app.
- **The nine-month support window and 0.87's removals** [DOC §3.6] mean a solo developer signs up to a recurring upgrade project, not a pinned baseline.
- **node-oracledb thin mode needs Oracle ≥ 12.1.** If the core is 11g we inherit an Instant Client deployment dependency we cannot size today (OQ-002).
- **No Expo config plugin.** [OBSERVED] the npm search returned no Uqudo Expo package, so the Uqudo module is a bare native module: Expo Go will not run it, and `expo prebuild` / EAS development builds are mandatory. That is fine, but it removes the main reason people reach for Expo.
- **No local precedent.** We would be the first to wire `uqudosdk-react-native` in this org. The *research* (JWS, error shape, image IDs) transfers; the code does not.

**Exit cost if wrong:** lowest of the four — one language means a pivot is a rewrite of one tier in a language you already have. This is B's second-best argument.

### Option C — Flutter mobile + Flutter Web back office + Spring Boot

**Languages: 2 — Dart, Java.** Attractive on paper: one UI framework, one design system, RTL solved identically in both tiers, and the back office inherits the mobile app's Arabic strings and theme.

**Why I do not recommend it.** A back office is a dense data application: tables of profiles, filters, per-row status, copy-paste of account numbers into other bank systems, and a statistics dashboard. Flutter Web renders to a canvas; text selection, browser find-in-page, native form autofill, and screen-reader behaviour are all weaker than DOM, and the initial payload is heavy for an internal tool that operators will open dozens of times a day on whatever hardware a Sudanese bank branch has. [UNVERIFIED — this is a well-known Flutter Web trade-off but I did not measure it for our case.] It also saves less than it appears: you still maintain a Java backend, so you are at two languages, not one, and you have traded the *best* back-office ecosystem for a merely adequate one to save one language you were going to keep anyway in Option A's back office.

Worth revisiting only if the back office turns out to be tiny — three screens, no data grid.

### Option D — Capacitor/Ionic + NestJS + React/TypeScript

| | |
|---|---|
| **Mobile** | Ionic/Capacitor, `uqudosdk-capacitor` 3.10.0, React inside the webview |
| **Backend** | NestJS 11, `node-oracledb` |
| **Back office** | React 19 + TypeScript + Vite (**shares components with the mobile app**) |
| **Languages** | 1 — TypeScript |

**The genuinely interesting one, and the closest thing to a real challenger to A.** It is one language *and* it fixes B's worst flaw: RTL in a webview is `dir="rtl"` + CSS logical properties, which is the best-supported RTL implementation in existence. Components, theme, Arabic strings and RTL handling are shared between the customer app and the back office. The Uqudo Capacitor plugin is current and released in lockstep [OBSERVED npm, 3.10.0, 2026-08-10].

**What it costs us.** Webview performance and feel for a consumer-facing bank app — acceptable for a form-heavy data-update journey, less so if the UX bar is set by native banking apps. Capacitor's plugin ecosystem is thinner than Flutter's and RN's for the incidental things a bank app needs (secure storage, screenshot blocking, certificate pinning, jailbreak detection); each of those is a research task rather than a known package. It also inherits B's node-oracledb/Oracle-11g uncertainty. And [UNVERIFIED] `uqudosdk-capacitor` appears to be the least-used of Uqudo's wrappers — I did not obtain download counts, so if we ever seriously consider D, get those numbers first.

**Exit cost if wrong:** low. Same as B.

### Named and dropped

| Dropped | Why |
|---|---|
| .NET MAUI + ASP.NET Core + Blazor | Uqudo gate: no NuGet distribution. §1. |
| Dart-everywhere (Flutter + Dart Frog/Serverpod) | Oracle gate: no first-party Dart driver. §3.5. |
| Kotlin Multiplatform / Compose Multiplatform | Uqudo gate: no SDK; you write the bridges. |
| Native Android + native iOS | Two mobile codebases for one developer; the Uqudo SDK supplies its own UI, so native buys almost nothing here. |
| Cordova | Current plugin, dead-end platform. |

---

## 5. What diverging from FIB actually costs — and what keeping it actually buys

Because the brief asks for this concretely, here it is with numbers rather than adjectives.

**If we keep Flutter + Spring Boot (Option A), what we inherit:**
- `uqudo_service.dart` — **~160 lines**, and the prior FIB research documents **two bugs in it**: it `jsonDecode`s a value that is a JWS compact string and will throw `FormatException` on a real device, and it reads `PlatformException.message`, which both native plugins leave null/empty, so every SDK error collapses to a generic string. [Prior research, `../FIB/docs/research/2026-06-10-uqudo-sdk-research.md`, §Q6 and Recommendation 2 — I did not re-verify the plugin internals myself; treat as [UNVERIFIED] until confirmed against 3.10.0.] The version of this file in the clone at `uqudo_service.dart` lines 34–44 and 55–67 has since been rewritten to parse the JWS payload and to `jsonDecode(e.code)` [OBSERVED] — so the bugs were fixed, but the file still parses the JWS **client-side**, which contradicts Uqudo's own guidance [DOC §1]. **We should not copy this file.** We should copy the shape and fix the layering.
- `UqudoServiceImpl.java` — **~115 lines** of genuinely reusable backend design: `client_credentials` form-post to `{url}/oauth/token`, Redis caching keyed to `expires_in` minus a TTL margin, and `GET {imageUrl}/info/img/{id}` with a bearer token. [OBSERVED] This is the most valuable single artefact in FIB and — critically — **it is framework-agnostic HTTP.** It transfers to NestJS in an afternoon.
- Backend patterns: `pom.xml` dependency set, `ApiClientService`, exception handling, `ApiResponse` envelope. Useful, not decisive.

**What we do NOT inherit under any option:** the Oracle stored-procedure call (§3.5 — FIB has none), the entire back office (§3.9 — FIB has none), a signed release build (§3.7), an iOS build (§3.7), and Arabic-first defaults (§3.4 — FIB defaults to English).

**Conclusion, stated plainly: the FIB code reuse argument is weak.** Diverging to Option B or D would cost roughly one to two days of re-derivation, not weeks, because the durable asset is the Uqudo *research*, which is language-independent and already written down. **I recommend Option A despite this, not because of it** — on RTL, Oracle, and SDK maturity, judged against our constraints.

---

## 6. The coupling argument — why these three go together

The three tiers are not independent, but they are not coupled the way one might assume.

1. **Uqudo does not couple the backend.** JWS verification, token issuance and image download are plain HTTPS + JOSE. Every candidate runtime does this well. [DOC §1, OBSERVED §3.5] **The backend choice is free of the Uqudo gate.**
2. **Oracle couples the backend hard.** `ProcessOmniCheckAct` with an OUT parameter, against an Oracle server whose version we do not know, on a host whose OS we do not control. Java's driver has no minimum-server cliff and no native dependency; Node's has both. **The backend choice is decided by Oracle, alone.** → Java/Spring Boot.
3. **The back office is decided by nothing external.** No SDK, no driver, no device. It needs Arabic RTL, a good data table and a chart or two. React + TypeScript wins in every option, so it is a **constant, not a variable.** → React/TS.
4. **Therefore the whole decision reduces to one question:** does the mobile tier get to be TypeScript, so that the backend could be dragged to Node and the whole stack collapse to one language? That is the only path to Option B's saving — and it requires accepting RN's RTL model (§3.4) *and* Node's Oracle cliff (§3.5) *and* a nine-month support treadmill (§3.6), to save two languages in tiers where being wrong is expensive.
5. **The trade I am recommending:** pay two extra languages to buy the best RTL implementation available, the safest Oracle path available, and the one Uqudo wrapper we have already read end to end. The back office stays TypeScript regardless, so the true incremental cost of Option A over B is **one extra language (Dart) plus one extra runtime (JVM)** — not three languages versus one.

---

## 7. Conditions under which the recommendation flips

Ordered by how likely they are to actually occur.

**F1 — Uqudo does not support `targetSdk 36`.** *Most urgent; Play's deadline is 2026-08-31, twelve days away.* If Uqudo confirms 3.10.0 is not validated at API 36, **every native option is blocked equally**, and the flip is not A→B but **A→PWA using `uqudosdk-web` 4.1.3**, which has no store gate at all. ACTION: email Uqudo support this week. Until answered, do not treat Option A as safe.

**F2 — OQ-003 (deadline) comes back under ~8 weeks.** Flips to **Option D (Capacitor)** or straight to the **Web SDK PWA**. Rationale, and it is strong: FIB shipped this exact journey — Sudan documents, Arabic, RTL, liveness — in a browser with `uqudosdk-web` [OBSERVED §3.9]. A PWA has no app-store review latency, no signing ceremony, no physical-device test matrix, and one codebase covering the customer journey and the back office. Costs: the COOP/COEP cross-origin-isolation headers required for the fastest WASM variant [DOC §1], no offline install story worth having, and a weaker security posture than a signed native app for a bank (no screenshot blocking, no jailbreak detection).

**F3 — The developer's TypeScript fluency substantially exceeds their Dart and Java.** Flips to **Option B or D**. For a solo developer over a multi-year maintenance horizon, fluency beats framework merit, and I would not argue otherwise. The task states existing exposure is the FIB codebase, which is Dart + Java + React — so this probably does not fire, but it is the flip I would take most seriously if it did.

**F4 — The bank's Oracle core is ≤ 11.2.** *Strengthens* A and *weakens* B and D: `node-oracledb` thin mode requires DB 12.1+ and we would need Instant Client on the backend host [DOC §3.5]. Also determines whether we pin `ojdbc8` (as FIB does [OBSERVED]) or `ojdbc17`. ACTION: this is a question for the bank, cheap to ask, and it partly settles A vs B. Ask it early.

**F5 — OQ-002 (data residency/hosting) mandates on-prem in the bank's data centre with a fixed OS image.** *Strengthens A.* A Spring Boot fat jar on a fixed JVM is the least-friction artefact to hand a bank ops team. A Node deployment needing Instant Client native libraries on a locked-down RHEL image is a worse conversation. If instead OQ-002 permits containers or managed cloud, this flip does not fire and B becomes relatively stronger.

**F6 — OQ-001 (regulatory regime) requires that identity images never leave a specific jurisdiction, or requires certified cryptography.** Does not change the mobile framework — Uqudo is a cloud service in all cases, and the image bytes transit Uqudo regardless. It *could* invalidate Uqudo entirely, which would restart this whole decision. It also affects whether a webview-based journey (D, PWA) is acceptable at all, since anti-tampering guarantees are weaker there. Flag, do not pre-empt.

**F7 — iOS turns out not to be required** (if the bank's customer base is overwhelmingly Android). Weakens cross-platform frameworks generally and makes **native Kotlin + Jetpack Compose** worth reconsidering: one platform, direct Uqudo Android SDK, no wrapper layer, and Compose has solid RTL via `LocalLayoutDirection`. It would also remove the Mac + Apple Developer Program cost. Worth an explicit product question before Sprint 1.

**F8 — OQ-004 (scale) comes back very large.** Does not move the mobile tier at all. Marginally favours the JVM for backend throughput, but at any plausible bank-customer-re-verification volume this is irrelevant. **This is the open question least likely to change the answer** — do not let it block the decision.

**F9 — The back office requirements grow to include grouping, aggregation and export.** Does not flip the stack; flips the component library toward Ant Design (MIT, free `Table`) and away from MUI (MUI X Pro licence). §3.8.

---

## 8. Recommended minimum supported versions — and why each number

| Tier | Setting | Value | Binding constraint |
|---|---|---|---|
| Android | `minSdkVersion` | **24** (Android 7.0) | **Flutter 3.47** floor is API 24 [DOC]. Uqudo's floor is 23 [OBSERVED], so Flutter binds. FIB chose 26 [OBSERVED] with no stated reason; 24 gives a wider device base in Sudan at no cost. |
| Android | `compileSdk` | **36** | Uqudo requires ≥ 35 [OBSERVED CHANGELOG 3.6.0]; Play targeting requires 36. |
| Android | `targetSdk` | **36** | Play, new apps and updates from 2026-08-31 [DOC]. **Conditional on F1.** |
| Android | ABIs | `armeabi-v7a`, `arm64-v8a`; AAB with ABI splits, `universalApk = false` | Uqudo supports no others [OBSERVED CHANGELOG 2.5.0]; [DOC] Android Prepare Environment. |
| Android | R8 | enabled in release, with `proguard-android-optimize.txt` + `proguard-rules.pro` | [DOC] Android Prepare Environment. Not configured in FIB [OBSERVED]. |
| iOS | deployment target | **15.0** | **Flutter 3.47** floor is iOS 15 [DOC]. Uqudo's floor is 12.0 [OBSERVED podspec], so Flutter binds. FIB's Podfile says 13.0 [OBSERVED] but FIB is on an older Flutter and has never built iOS. |
| iOS | build toolchain | **Xcode 26 / iOS 26 SDK** | Apple, in force since 2026-04-28 [DOC]. |
| Java | runtime | **21 LTS** | Spring Boot 4.1 requires ≥ 17, supports to 26 [DOC]. 21 is the current LTS with the longest runway. |
| Spring Boot | version | **4.1.x** | Current GA [DOC]. |
| Back office | browsers | evergreen Chrome/Edge/Firefox/Safari, last 2 versions | **[UNVERIFIED]** — I have no information about what browsers bank operators actually run. If any operator workstation is on a locked, old browser this number is wrong. **Confirm before Sprint 1.** |
| Back office | Node (build) | **≥ 22** | Vite/React toolchain baseline; also NestJS's floor if we ever move backend. |

Two deliberate non-recommendations: **NFC is not required** by our constraints (document scan + liveness only), so we do not need iOS 13+ for NFC, the NFC entitlement, the ISO7816 `Info.plist` AIDs, or the "Near Field Communication Tag Reading" capability [DOC iOS Prepare Environment]. Dropping NFC removes a meaningful chunk of iOS setup. And **we should not mirror FIB's Flutter 3.38.7 pin** — start on current stable and pin it via `fvm` at the first release.

---

## 9. What I could not determine

1. **Whether Uqudo Android SDK 3.10.0 supports `targetSdk 36`.** The changelog stops at compileSdk 35 (3.6.0). Nothing in the retrievable docs addresses 36 or 37. This gates the Play deadline of 2026-08-31 and gates flip condition F1. **Must be asked of Uqudo support.** I did not guess.
2. **Which languages the Uqudo SDK's own UI ships with, and whether Arabic is among them.** The SDK renders its own full-screen scan and liveness UI, so this directly determines whether our Arabic-first requirement is satisfiable without string overrides. The docs page says only *"you can add your string resources and even can override the current resource as per your application"* [DOC [General Strings](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/ui-customisation/text-and-language/general-strings.md)] and never lists a language set. Strong circumstantial evidence Arabic is supported — Uqudo markets itself as MEA-focused [OBSERVED plugin README], and the changelog repeatedly fixes RTL layout (3.1.3 Kurdish, 3.4.1 iOS RTL, 3.6.0 iOS RTL) [OBSERVED] — but **circumstantial is not verified.** A "98 languages" figure surfaced in search results but refers to OCR/document processing, not UI strings; I am not relying on it. **Ask Uqudo, and confirm on a device.**
3. **The Flutter plugin's `DocumentType` enum at 3.10.0.** I verified `SDN_ID`/`SDN_DL`/`SDN_VL` in the **3.8.0+1** source in the local pub cache [OBSERVED]. 3.10.0 is not in the local cache and I did not download it. Removal is implausible, but unverified.
4. **React Native 0.87's exact `minSdkVersion` and iOS deployment target.** The 0.87 release post states `compileSdk` 37 and `minCompileSdk` 34 but not `minSdkVersion`; iOS is not stated at all. Last confirmed iOS bump is 15.1 at 0.76 [DOC]. If Option B is ever seriously reconsidered, get these from the RN template `build.gradle` and `Podfile` directly.
5. **How the Uqudo .NET binding is actually distributed**, given nothing is on NuGet. I did not fetch the GitHub sample, per the repo's hard rule against web-fetching repository contents. Moot given the drop, but noted.
6. **Whether the Uqudo Web SDK reaches feature parity with native** for document scan and liveness quality, and its browser/mobile-browser support matrix. The `web.md` and `sdk-installation.md` pages returned only the WASM/COOP/COEP material and a CORS note [DOC]; no browser matrix, no parity statement. Since this underpins flip conditions F1 and F2, **it needs its own research session if either fires.** What I do have: FIB ran it in production for Sudan documents including liveness [OBSERVED §3.9], which is a strong existence proof but not a quality measurement.
7. **`uqudosdk-capacitor` adoption.** No download counts obtained. Needed before Option D could be recommended.
8. **What `https://mb1.sfbank-sd.com` is built with, and its visual language.** The page is a client-rendered SPA titled "Pearl"; fetching returns no meaningful DOM. [OBSERVED] Colours, logo and layout — the stated purpose of the benchmark — need a human to open it in a browser, or a screenshot. This does not affect AD-001; it will affect the back-office design task.
9. **The bank's Oracle Database server version.** Determines `ojdbc8` vs `ojdbc17` and gates flip condition F4. Not knowable from here.
10. **Back-office operator browser and hardware baseline.** Affects the minimum-version table and would affect Option C's viability.

---

## 10. Risks

| # | Risk | If it happens | Cost to reverse |
|---|---|---|---|
| R1 | **Uqudo not `targetSdk 36`-ready before 2026-08-31** | No native Android release is publishable on Play. Affects A, B and D identically. | Play extension to 2026-11-01 is available and free [DOC]. Beyond that: wait for Uqudo, or pivot the customer tier to the Web SDK. **Days if caught now, weeks if caught in Sprint 3.** Mitigate by asking Uqudo this week. |
| R2 | **Uqudo SDK UI does not ship Arabic** | The single most visible screen in the journey — document scan — appears in English in an Arabic-first bank app. | Override strings via Android `values-ar` resources and iOS `uq-text.plist` [DOC]. Recoverable, but it is a per-string translation task across scan, face-recognition and general string sets, and must be redone at each SDK upgrade. Estimate 2–4 days plus recurring. |
| R3 | **Oracle core is 11g and we chose a Node backend** | Only relevant under B/D. `node-oracledb` thin mode cannot connect; Instant Client must be installed and version-managed on the backend host. | Solvable but adds a permanent native dependency to deployment. Option A is immune. |
| R4 | **iOS build is materially harder than assumed** | FIB never built iOS [OBSERVED]. The exact-pinned `OpenSSL-Universal` and the arm64 requirement are known friction points. | Bounded — this is one spike, not a redesign. But **do the iOS spike in Sprint 1, not Sprint 5.** Discovering an iOS blocker late is the most expensive version of this risk under every option. |
| R5 | **Three languages overwhelm one developer** — the main risk of choosing A over B | Velocity decays; the back office or the tests get starved. | Reversible per tier. The back office and backend survive any mobile pivot; the Uqudo backend work survives entirely because it is plain HTTPS. **Worst case is a mobile rewrite — one tier, not the system.** |
| R6 | **We build a native app when a PWA would have shipped in a third of the time** | FIB already proved the browser path works for this exact journey and document set [OBSERVED]. If OQ-003 comes back tight, choosing native was the wrong call. | Very high if discovered late — a native app and a PWA share almost no client code. **This is the risk I would most want retired first: get OQ-003 answered before Sprint 1 opens.** |
| R7 | **The client-side JWS-parsing pattern is copied from FIB** | Copying `_parseJwsResult` [OBSERVED `uqudo_service.dart` lines 34–44] carries FIB's contradiction of Uqudo's own security guidance into our codebase — an unverified signature on identity data in a bank app. | Cheap to avoid now, expensive to unwind after the mobile↔backend contract is set. **Design the contract so the raw JWS goes straight to the backend, from day one.** This is an AD-002 decision; I flag it here only so AD-001 does not accidentally foreclose it. |
| R8 | **MUI X licence cliff in the back office** | Back-office requirements grow into grouping/aggregation/export; MUI X Pro is a paid licence. | Trivial if Ant Design is chosen at the start; a component-library migration if discovered in month six. §3.8. |

---

## 11. Card updates

No `docs/components/` directory exists in this repository. [OBSERVED — glob for `docs/components/*.md` returned no files.] This was in part an SDK investigation, so I am drafting the card. **Suggested path: `docs/components/uqudo-sdk.md`.** I cannot write it; the calling session should create it from the text below.

````markdown
# Component: Uqudo eKYC SDK

Status: researched, not integrated · Last verified: 2026-08-19 · Source: docs/sessions/2026-08-19-research-ad-001-stack.md

## What it is
Third-party eKYC provider (MEA-focused). Supplies its own full-screen UI for document
scan and facial liveness. Requires a commercial licence and a tenant. [OBSERVED
plugin README.md line 20]

## Docs — machine-readable
- Index: https://docs.uqudo.com/llms.txt  [DOC]
- Any page: append `.md` to the URL for a markdown variant. [DOC]
- Caveat: `integration/flutter.md`, `integration/react-native.md`, `integration/web.md`
  and `integration/dotnet.md` contain only a pointer to a GitHub sample; the technical
  content lives under `integration/android/*`, `integration/ios/*`,
  `integration/web/*`. [OBSERVED]

## Platform matrix (as of 2026-08-19)
| Platform | Package | Version | Released |
|---|---|---|---|
| Android native | `io.uqudo.sdk:Uqudo` @ https://rm.dev.uqudo.io/repository/uqudo-public/ | 3.10.0 | — [DOC] |
| iOS native | pod `UqudoSDK` + `OpenSSL-Universal` 3.3.3001 (exact) | 3.10.0 | — [DOC] |
| Flutter | `uqudosdk_flutter` (pub.dev) | 3.10.0 | 2026-08-10 [OBSERVED] |
| React Native | `uqudosdk-react-native` (npm) | 3.10.0 | 2026-08-10 [OBSERVED] |
| Capacitor | `uqudosdk-capacitor` (npm) | 3.10.0 | 2026-08-10 [OBSERVED] |
| Cordova | `uqudosdk-cordova` (npm) | 3.10.0 | 2026-08-10 [OBSERVED] |
| Web | `uqudosdk-web` (npm), WASM, separate 4.x line | 4.1.3 | 2026-06-24 [OBSERVED] |
| .NET | doc page + GitHub sample only — **nothing on NuGet** | n/a | [OBSERVED] |

Cadence: a release every ~6–10 weeks since 2025-05; all mobile wrappers ship the same
day. [OBSERVED pub.dev + npm publish dates]

## Platform floors
- Android `minSdkVersion` **23** (since 3.8.0), `compileSdk` **≥ 35** (since 3.6.0),
  `targetSdk` 34 mandatory since 3.0.0. [OBSERVED CHANGELOG + plugin build.gradle]
- Android ABIs: **armeabi-v7a and arm64-v8a only**. x86_64 builds but the SDK does not
  function → **an arm64 physical device is required for any Uqudo testing.**
  [OBSERVED CHANGELOG 2.5.0]
- iOS **12.0**; **13.0+** only if NFC is used. arm64. [OBSERVED podspec; DOC]
- ⚠️ **`targetSdk 36` support is NOT DETERMINED.** Google Play requires 36 for new apps
  and updates from 2026-08-31 [DOC]. **Confirm with Uqudo support.**

## Sudan
`DocumentType` includes `SDN_ID`, `SDN_DL`, `SDN_VL`. [OBSERVED uqudosdk_flutter 3.8.0+1
lib/uqudosdk_flutter.dart lines 28–30]. Verified in production use for Sudan by the FIB
reference, on both the Flutter app and the Web SDK. [OBSERVED]

## Language / RTL
- SDK renders its own UI. **Which languages ship built in — including whether Arabic is
  among them — is NOT DETERMINED.** [DOC pages do not state it]
- Strings are overridable per platform (Android string resources; iOS `uq-text.plist`).
  [DOC]
- The changelog repeatedly fixes RTL layout (3.1.3 Kurdish, 3.4.1 iOS, 3.6.0 iOS), so
  RTL is supported. [OBSERVED CHANGELOG]
- iOS note: setting `UIView.appearance().semanticContentAttribute = .forceRightToLeft`
  globally used to affect the SDK's navigation bar; fixed in 3.6.0. [OBSERVED CHANGELOG]

## Results — read this before writing any parsing code
- `enroll()` and `faceSession()` return a **JWS compact-serialization string**
  (`header.payload.signature`, RS256), **not JSON**. Parse and verify **server-side
  only**; JWKS at `https://id.uqudo.io/api/.well-known/jwks.json`. [DOC
  sdk-result/validation-and-parsing]
- Images are **references, not bytes**: `frontImageId`, `backImageId`,
  `frontFrameImageId`, `backFrameImageId`, `faceImageId`, `face.auditTrailImageId`.
  Retrieve separately with the access token. [DOC scan-object / face-object]
- ⚠️ The FIB reference parses the JWS **client-side**
  (`../FIB/mobile/lib/core/uqudo/uqudo_service.dart` lines 34–44) [OBSERVED]. **Do not
  copy this.** Design our mobile↔backend contract to forward the raw JWS.

## Backend surface (framework-agnostic, plain HTTPS)
- Token: `POST {base}/oauth/token`, `grant_type=client_credentials`, form-encoded,
  `expires_in` 1800s. Cache it. [OBSERVED ../FIB/.../UqudoServiceImpl.java lines 46–73]
- Image: `GET {imageBase}/info/img/{imageId}` with `Authorization: Bearer …`.
  [OBSERVED same file lines 76–108]
- Client credentials live **only** in the backend. Never in the app.

## Release-build requirements
- Android: R8/ProGuard config required (`proguard-android-optimize.txt` +
  `proguard-rules.pro`); ABI splits with `universalApk = false`, or AAB. [DOC]
- Flutter plugin injects the Uqudo Maven repo via `rootProject.allprojects` — breaks
  under `dependencyResolutionManagement { repositoriesMode = FAIL_ON_PROJECT_REPOS }`.
  [OBSERVED plugin android/build.gradle lines 17–22]
- iOS: `OpenSSL-Universal` pinned to exactly 3.3.3001; other versions crash the SDK.
  [OBSERVED podspec; CHANGELOG 3.5.0]

## Open items
- [ ] Confirm `targetSdk 36` support (blocks Play submission after 2026-08-31)
- [ ] Confirm the SDK UI ships Arabic; if not, scope the string-override work
- [ ] Obtain a Uqudo tenant + credentials for Sudan document types
- [ ] Verify `SDN_ID`/`SDN_DL`/`SDN_VL` still present at 3.10.0
- [ ] Web SDK browser matrix and native-parity, **only if** flip F1 or F2 fires
````

**PROJECT_PLAN.md — proposed edits (for the calling session to apply):**
- Line 14–15, replace `Minimum supported versions: TBD — AD-001.` with the §8 table.
- Decisions log — add: `AD-001 | 2026-08-19 | Flutter + Spring Boot/Java + React/TS | Uqudo SDK gate, RTL quality, Oracle driver maturity | docs/sessions/2026-08-19-research-ad-001-stack.md`, **marked provisional pending R1**.
- Open questions — add **OQ-006: what Oracle Database version does the bank's core run?** It gates the JDBC/driver pin and flip condition F4, it is cheap to ask, and it partly decides A vs B.
- Line 32–34 — note that OQ-003 is now the highest-value open question for AD-001 (flip F2), and OQ-004 the lowest (flip F8).

**Nothing in this report contradicts a settled decision in PROJECT_PLAN.md** — the decisions log is empty. Two findings do contradict *implications* of the plan's framing, and I flag them rather than bury them: (1) PROJECT_PLAN line 50 describes `Web_Code` as "a web front end" — it is specifically the **customer onboarding journey using the Uqudo Web SDK**, not a back office, so FIB offers no back-office precedent at all; (2) the plan's framing of FIB as the Uqudo precedent is correct for the *mobile* tier but not the *Oracle stored-procedure* work, which FIB does not contain in any form.

---

## Noticed in passing

Not investigated further; recorded so it is not lost.

- FIB's mobile app depends on `screen_protector: ^1.5.2` [OBSERVED `pubspec.yaml` line 44] — screenshot/recording blocking. Directly relevant to our "PII and identity-document images" constraint, and a Flutter package exists for it. Whether equivalents exist for Capacitor is unknown.
- FIB's Flutter app also pulls `flutter_secure_storage`, `flutter_image_compress`, `dio_smart_retry` and `crypto` [OBSERVED `pubspec.yaml`] — a reasonable starting dependency set for our mobile tier.
- FIB's backend uses Redis to cache the Uqudo token against `expires_in` [OBSERVED `UqudoServiceImpl.java` line 58]. Sensible, and it means the backend needs Redis or an equivalent cache — an AD-002 input.
- FIB bundles three font families including `TheSansArabic` and `BJadidBold`, shared between the Flutter app and the web bundle [OBSERVED `pubspec.yaml` lines 75–84 and `Web_Code/static/media/`]. If we benchmark visual language against `mb1.sfbank-sd.com`, the Arabic typeface choice is likely already made for us — worth confirming licensing before reuse.
- FIB's `pom.xml` has `spring-boot-devtools` at runtime scope and `javax.validation:validation-api:1.1.0.Final` — the latter is the pre-Jakarta namespace and is dead weight on Spring Boot 3+ [OBSERVED lines 46–51, 77–81]. Do not copy the dependency block verbatim.
- FIB's Uqudo Flutter wrapper does not call `enableBackgroundCheck()`; the FIB backend runs a separate background-check call instead. Not in our constraint set, so out of scope — but if AML screening ever becomes a requirement, the SDK-integrated path exists and is Uqudo's recommended one.

---

**Sources:**
[Uqudo docs index (llms.txt)](https://docs.uqudo.com/llms.txt) ·
[Uqudo Android · Prepare Environment](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/prepare-environment.md) ·
[Uqudo iOS · Prepare Environment](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/ios/prepare-environment.md) ·
[Uqudo Web · SDK Installation](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/web/sdk-installation.md) ·
[Uqudo Web SDK changelog](https://docs.uqudo.com/docs/kyc/uqudo-sdk/changelog/web-sdk.md) ·
[Uqudo · Validation and Parsing](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/validation-and-parsing) ·
[Uqudo · Scan Object](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/scan-object) ·
[Uqudo Android · General Strings](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/ui-customisation/text-and-language/general-strings.md) ·
[pub.dev · uqudosdk_flutter changelog](https://pub.dev/packages/uqudosdk_flutter/changelog) ·
[Flutter supported platforms](https://docs.flutter.dev/reference/supported-platforms) ·
[Flutter internationalization](https://docs.flutter.dev/ui/accessibility-and-internationalization/internationalization) ·
[React Native 0.87 release](https://reactnative.dev/blog/2026/08/11/react-native-0.87) ·
[React Native RTL support](https://reactnative.dev/blog/2016/08/19/right-to-left-support-for-react-native-apps) ·
[React Native release support policy](https://github.com/reactwg/react-native-releases/blob/main/docs/support.md) ·
[React Native iOS 15.1 minimum announcement](https://github.com/react-native-community/discussions-and-proposals/discussions/812) ·
[Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) ·
[node-oracledb introduction](https://node-oracledb.readthedocs.io/en/latest/user_guide/introduction.html) ·
[Oracle Free Use Terms and Conditions](https://www.oracle.com/downloads/licenses/oracle-free-license.html) ·
[Google Play target API level requirements](https://developer.android.com/google/play/requirements/target-sdk) ·
[Apple upcoming requirements](https://developer.apple.com/news/upcoming-requirements/) ·
[MUI right-to-left](https://mui.com/material-ui/customization/right-to-left/) ·
[Ant Design ConfigProvider](https://ant.design/components/config-provider)
