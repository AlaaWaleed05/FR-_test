# Research — Splash motion and theme-level surface treatment (pre-pilot visual upgrade)

**Date:** 2026-09-07 · **Scope:** two items only — splash motion, and a theme-level surface/background treatment. Options for the PO to choose from. No implementation.

**Reading I pursued.** The task is unambiguous about the two items. Where it left room, I took the most defensible reading: *"theme-level treatment that reaches every screen without restyling each screen's components"* means changes confined to `AppTheme.light()` and at most `FruApp`'s existing `builder` — **zero edits to any screen file**. Options that need per-screen edits are still presented, but their edit cost is stated as a cost, not hidden. Readings I did **not** pursue: a design-token system / theme extension layer (that is the post-pilot design system, explicitly out of scope), and dark mode (D3.4 settled light-only).

---

## 1. Summary — what is being decided

| | Recommended default | Runner-up |
|---|---|---|
| **Splash motion** | **P2 — "One light pass"**: the existing `_sweep` shader promoted from a decoration on one line to the single organising gesture of the whole lockup | P1 — "Lit field" (radial brand glow behind the emblem) |
| **Surface treatment** | **S1 — "Tinted canvas, white containers"**: `scaffoldBackgroundColor` moved off `colorScheme.surface` onto `surfaceContainerLow`, and every field/list/card given `surfaceContainerLowest` (white) through theme data alone | S3 — "Gradient header band", additive on top of S1 |

**Why this pair.** S1 is the single highest-leverage change available: it is roughly 25 lines in one file, touches no screen, costs literally nothing extra to render (flat fills — no gradient, no shadow, no `saveLayer`), needs **no low-end fallback at all**, and is direction-neutral by construction because colours have no direction. P2 keeps the one genuinely distinctive thing the current splash already does, makes it the whole idea rather than a footnote, fixes a latent RTL hardcode for free, and has a clean, defined fallback that removes the shader entirely.

**The single most important finding in this report** is a diagnosis, not an option: today the page canvas is `ColorScheme.surface` and a `Card` is `surfaceContainerLow` — two tones that are *nearly the same value*. That is the mechanical reason nothing on any screen appears to lift off anything. Fixing it does not require a gradient, a blur, or a package. See §2.3 and §5.1.

**One honest tension, flagged up front.** The PO asked for a "subtle brand-tinted gradient". The most authoritative Arabic-first design system this project already cites for other rules states the opposite preference: *"Flat UI/Soft UI … strips away excessive visual flourishes like shadows, gradients, and 3D effects in favor of clean, minimalist, and highly functional design"* [SOURCED — https://designsystem.gov.ae/guidelines/mobile-applications, fetched this session]. I am not using that to refuse the ask. I am using it to argue that the *tonal-step* approach (S1) delivers "modern surface" more reliably than a gradient does, and that if a gradient is wanted it should be **confined to a header zone** (S3) rather than washed across the whole canvas.

---

## 2. Current state, from the code

### 2.1 The splash — `mobile/lib/features/entry/launch_screen.dart`

[CODE] One `AnimationController`, 1800 ms, five `Interval` sub-animations off it:

| Element | Interval | Mechanism |
|---|---|---|
| Emblem | 0.00–0.42 | `Opacity` + `Transform.scale` 0.92→1.0, `Image.asset` with `cacheWidth` |
| Wordmark «الفرنسي بياناتي» | 0.20–0.52 | `_rise` = `Opacity` + `Transform.translate(0, 12→0)`, inside `FittedBox` |
| Slogan «لؤلؤة المصارف» | 0.42–0.78 | `_sweep` = `ShaderMask(BlendMode.dstIn)` with a 3-stop `LinearGradient`, soft head at `t*1.12`, tail at `head-0.10` |
| Hairline `_rule` | 0.62–0.84 | `Container(width: 44 * t)` — a per-frame **layout** change |
| Tagline «خطوات بسيطة لتحديث بياناتك» | 0.72–1.00 | `_rise` |
| Spinner | 0.84–1.00 | `_rise` around a stock `CircularProgressIndicator` |

Layout: `Scaffold(backgroundColor: AppTheme.brandBlue)` → `SafeArea` → `Padding(horizontal: 32)` → `Column` of `Expanded(flex: 5)` (lockup, centred) and `Expanded(flex: 2)` (status, top-aligned).

Non-blocking is real and structural [CODE lines 67–72]: `ref.listen` calls `_navigate` the instant `launchDecisionProvider` resolves; nothing awaits `_controller`. Reduced motion is handled in `didChangeDependencies` by setting `_controller.value = 1` [CODE lines 47–50] — it jumps to the finished composition rather than skipping content.

**Five specific weaknesses, each with a named fix.** These are what "not modern enough" most plausibly decomposes into:

1. **[CODE/INFERRED] Four of the five elements use the same gesture.** Emblem, wordmark, tagline and spinner are all "fade + move a little". The slogan sweep is the only distinct idea on the screen and it is applied to the *smallest, most muted* line. The composition has no single moment.
2. **[CODE] The field is one flat colour with zero depth.** `#105097` edge to edge. This is deliberate (D2.3 — so the master artwork's own blue field merges invisibly) and it is also exactly what reads as "template". The two goals are reconcilable: a radial tone shift *centred on the emblem* keeps the merge at the emblem and adds depth at the edges.
3. **[CODE line 217–222] `_sweep` hardcodes `Alignment.centerRight` → `Alignment.centerLeft`.** This is **not a live bug** — the app forces `Directionality.rtl` app-wide [CODE `app.dart:27-28`] and supports only `Locale('ar')` [CODE `app.dart:20-21`], so physical-right *is* the reading start. It is a latent hazard of exactly the class D6.1 eliminated at the page-transition level, and the fix is free (see §6.3).
4. **[DOC] `_rise` and `_logo` use the `Opacity` widget inside an animation.** Flutter's own guidance: *"Avoid using the `Opacity` widget, and particularly avoid it in an animation. Use `AnimatedOpacity` or `FadeInImage` instead"* and *"it's usually faster to just draw them with a semitransparent color"* [https://docs.flutter.dev/perf/best-practices, fetched this session]. `FadeTransition` is the drop-in replacement and is free.
5. **[CODE line 257] The status element is a stock `CircularProgressIndicator`.** On a screen whose entire job is brand impression, the last thing that appears is the most generic widget Material ships.

**One thing that is already right and must be preserved in every option:** `_sweep`'s reason for existing. A mask reveal over correctly-shaped Arabic, never a growing substring — the rationale is written into the code comment [CODE lines 195–204] and was a PO-level decision recorded in the S8-03 report §5.

### 2.2 The theme — `mobile/lib/core/theme/app_theme.dart` (82 lines)

[CODE] Settled and not reopened here: `brandBlue = #105097`; `ColorScheme.fromSeed(seedColor: brandBlue).copyWith(primary: brandBlue, onPrimary: Colors.white)`; `useMaterial3: true`; `brightness: Brightness.light`, no `darkTheme`; `fontFamily: 'IBMPlexSansArabic'` with `fontFamilyFallback: ['sans-serif']`; a full `TextTheme` at `letterSpacing: 0`, body 16 sp, `height: 1.6`; `pageTransitionsTheme` = `FadeUpwardsPageTransitionsBuilder` for Android only.

**What is absent:** `scaffoldBackgroundColor`, `appBarTheme`, `cardTheme`, `inputDecorationTheme`, `listTileTheme`, `dividerTheme`, `filledButtonTheme`. Nothing in the theme says anything about surfaces.

### 2.3 What that means, verified against the pinned SDK

[OBSERVED — `C:\Users\DELL\fvm\versions\3.47.0\packages\flutter\lib\src\material\theme_data.dart:453`]
```dart
scaffoldBackgroundColor ??= colorScheme.surface;
```
[OBSERVED — `scaffold.dart:3237`] `Scaffold` paints `widget.backgroundColor ?? themeData.scaffoldBackgroundColor`.

So the app's canvas today is **`scheme.surface`** — not `Colors.white`, but a near-white M3 neutral with a faint blue cast, which reads as white. Meanwhile M3's `Card` defaults to `surfaceContainerLow`, one small tonal step away. **The canvas and the containers are almost the same value.** That is the whole diagnosis: it is not that the app lacks cards, it is that a card and the page behind it are the same colour, so nothing can lift.

[OBSERVED — `color_scheme.dart:435-443`] `ColorScheme.fromSeed` populates `surfaceContainerLowest` / `surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh` / `surfaceContainerHighest` from `MaterialDynamicColors` — i.e. **derived from the tonal palette, not hand-picked**, which is precisely what D2.1 requires ("Shades for pressed states and containers are derived from the Material 3 tonal palette, not hand-picked").

[SOURCED — https://api.flutter.dev/flutter/material/ColorScheme-class.html] The role descriptions: `surfaceContainerLowest` — *"the lightest tone and the least emphasis relative to the surface"*; `surfaceContainer` — *"A recommended color role for a distinct area within the surface"*; `surfaceContainerHighest` — *"the darkest tone … the most emphasis against the surface"*.

[OBSERVED] The theme hooks needed all exist on `ThemeData` in 3.47.0, and **their types have moved to the `…Data` suffix** — a real trap for anyone writing this from memory:
- `theme_data.dart:1005` → `final InputDecorationThemeData inputDecorationTheme;` (**not** `InputDecorationTheme`)
- `theme_data.dart:1327` → `final CardThemeData cardTheme;` (**not** `CardTheme`)
- `theme_data.dart:1381` → `final ListTileThemeData listTileTheme;`
- `input_decorator.dart:4905, 5156` → `InputDecorationThemeData` carries `filled` and `fillColor`.

### 2.4 What the surface treatment lands on

[CODE `account_entry_screen.dart`] `Scaffold(appBar: AppBar(title: Text('بيانات الحساب')), body: Padding(all: 16, child: Column(...)))`. Inside: a `DropdownButtonFormField`, one `TextField` with `InputDecoration(labelText:, errorText:)`, a bare `Text` for the unreachable message, a `FilledButton`. **No container of any kind.** Every control sits directly on the canvas.

[CODE `stage3_screen.dart:314-493`] The canonical data-entry shape: `Scaffold(appBar:, body: Column[ OfflineBanner?, Expanded(SingleChildScrollView(padding: 16, Column[...])), Padding(all: 16, FilledButton) ])`. Section headings are `Text(..., style: textTheme.titleSmall)` with `SizedBox(height: 16)` between groups. Controls are `TextField`, `RadioGroup`+`RadioListTile`, `PickerField`. Again, **no containers** — the "sections" are whitespace only.

[CODE] `stage9_screen.dart:369` has the app's one `Card`.

**Consequence [INFERRED]:** because the screens are built from bare Material controls on a bare canvas, a theme that colours *the canvas* and *the controls' own fills* differently produces containment on every screen without a single screen edit. The screens don't need cards; the fields become the cards.

---

## 3. References, transfer-analysed

Provenance is stated per reference. I did not install or directly observe any of these apps. Every claim about how an app *looks* is secondhand unless marked otherwise, and I say which.

### 3.1 Revolut — PO-named polish reference

**What it is.** LTR, English-first consumer neobank for daily use on flagship hardware. Described in secondary design analyses as *"sleek dark interface, gradient cards, fintech precision"*, and as using *"soft premium-product elevation where surfaces lift cleanly, while contrast, device frames, and card imagery do much of the visual work"* [SOURCED — search summary over https://getdesign.md/revolut/design-md and https://open-design.ai/plugins/design-system-revolut/; both are secondary/AI-assembled design-system scrapes, **not** Revolut's own documentation. Treat every specific as UNVERIFIED]. The prior research already logged the same provenance caveat [CODE `docs/sessions/2026-09-07-research-ui-ux-design-plan.md` §3.1].

**Borrow — for splash.** *One confident move, not several small ones.* The Revolut-class quality signal is a single gesture executed precisely, with generous timing and a clean settle, rather than a checklist of elements each doing a small fade. This is the argument for P2 over the current five-element stagger.

**Borrow — for surface.** *"Surfaces lift cleanly."* The lift comes from a **tonal step plus a radius**, not from a heavy shadow. Directly supports S1.

**Where the vibe does NOT fit.** [Restating §3.1 of the existing research, which is settled and which I am not relitigating.] Revolut's register is **persuasion** — it is selling a choice. Our customer has been *told by their bank* to do this, once, and is mildly stressed. Celebratory motion, spring overshoot, confetti-adjacent flourish and marketing-tone copy read as *less* trustworthy in a mandated identity flow and can trigger a "is this a scam?" reflex — which the existing research names as **the most important non-transfer**, and I agree. Also non-transferable: LTR type rhythm; dark mode (D3.4 is light-only); translucency/blur/animated cards (banned on the device floor, §7); daily-use dashboard density (every screen here is seen exactly once).

**Concretely, the line I would draw:** borrow Revolut's *precision* and *restraint*. Refuse its *energy*.

### 3.2 Monzo — the surface reference, and the most directly applicable one

**What it is.** UK consumer bank with a published design practice; the official blog documents a modular design system built on semantic colour tokens [SOURCED — https://monzo.com/blog/reimagining-monzo-com-building-a-modular-high-fidelity-web-design-system-that-scales, official Monzo blog].

**The transferable finding.** From the search-result synthesis over Monzo design-system documentation pages: *"Depth and section separation come entirely from alternating backgrounds rather than elevation"*, with product pages using a cool tinted surface for content cards against a plain page background, and the accent colour reserved for a *"moment of delight and brand personality rather than for warning states"* [SOURCED — search summary over https://www.shadcn.io/design/monzo, https://oh-my-design.kr/design-systems/monzo, https://www.designmd.co/d/monzo. **These are third-party design-system scrapes, not Monzo's own spec. The hex values they cite are UNVERIFIED.** The *principle* is what I am taking, and it is independently corroborated by the M3 surface-container model in §2.3.]

**Why this is the best fit of any reference here.** "Depth from alternating flat backgrounds, not elevation" is *simultaneously* the modern look the PO wants and the cheapest possible thing to render on a legacy OpenGL renderer: a flat fill, no blur, no `saveLayer`, no shadow blur pass. This is the direct source for **S1** and **S2**.

**Where the vibe does NOT fit.** Monzo's brand energy is a hot coral accent used as delight — wrong register for a mandated bank chore, and our accent is already fixed at `#105097` and reserved for the primary action (D2.1). Monzo is LTR/English with a display typeface tuned for Latin. Its "one card, one product" mental model assumes a returning daily user. And the specific cited surfaces are *web* pages, not an Android app on a 2 GB phone.

### 3.3 UAE Design System — the Arabic-first authority, including where it disagrees with the ask

**What it is.** A government-published, Arabic-first, bilingual design system with explicit mobile-app guidance — one of very few authoritative RTL sources, already cited by this project for typography and motion.

**Direct quotes, fetched this session** [SOURCED — https://designsystem.gov.ae/guidelines/mobile-applications]:
- Preferred approach is **Flat UI / Soft UI**, which *"strips away excessive visual flourishes like shadows, gradients, and 3D effects in favor of clean, minimalist, and highly functional design."*
- *"Micro-interactions are small, subtle animations and transitions that provide feedback to user actions and guide them through the app."*
- *"Ensure to follow accessibility guidelines in terms of animations and the ability to detect motion settings from the device's settings — thereby adapting the animations in the app."*
- Arabic header order: *"[User Profile/Avatar] [Notifications Icon] [Page Title] [Back Button (if applicable)]"*.

**Borrow.** The flat-UI position is the strongest external argument for S1 and S2 over a heavy gradient wash, from an Arabic-first source rather than a performance argument. The motion-settings requirement is already satisfied [CODE `launch_screen.dart:47-50`] and must survive whatever replaces the splash.

**Where it does NOT transfer.** It is a Gulf *government* brand system, web-first, assuming high-end devices and a government register — an emblem-and-seal visual language, not a commercial bank's. Its typography recommendation (Noto Kufi for body) was already rejected by this project on legibility grounds [D3.1 / prior research §3.3], and that rejection stands. And its flat-UI stance is a *preference statement*, not evidence that a gradient harms comprehension; the PO can override it, which is why S3 is presented rather than refused.

### 3.4 Mashreq Neo (UAE) — the RTL/Arabic gradient precedent

**What it is.** One of the UAE's first digital banks (Mashreq, launched October 2017), Arabic/English. Its visual identity is described as featuring *"expressive elements such as 3D graphics and emotive human photography, with a color palette dominated by orange hues and gradients aligned with the parent company's traditional branding"* [SOURCED — search summary over https://fintechbranding.studio/top-neobank-brands-in-dubai-the-uae-in-2025, a branding-agency industry survey. **Secondary. I did not observe the app.**]

**Borrow.** It is a real, shipping, RTL-capable *bank* app that uses a **parent-brand-derived gradient** as its surface signature. That is the existence proof that S3 is not a foreign idea in this market: a gradient built from the bank's own single colour reads as *brand continuity*, not as decoration. It is also the argument for deriving the gradient's two endpoints from the pinned `#105097` and its tonal neighbours rather than inventing a second hue.

**Where it does NOT transfer.** Neo targets an affluent, high-device-baseline UAE market and leans on 3D graphics and photography — both banned here (memory, decode cost, and the "mandated chore, not a lifestyle product" register). Its "emotive human photography" is precisely the persuasion register §3.1 rules out. And it is a daily-banking product; ours is once-ever.

### 3.5 STC Bank (Saudi) — Arabic-first, and the "restraint plus one signature" model

**What it is.** Saudi digital bank grown out of STC Pay. Its design partner describes a *"modern, confident, and distinctly Saudi personality … conveyed through custom illustrations and a refined iconography suite"*, with the dashboard *"rethought around intent, with primary actions surfaced while supporting tools remain readily available"* [SOURCED — https://clay.global/work/stc-bank, fetched this session. **The page is strategy prose plus imagery; it contains no colour values, no gradient specification and no RTL implementation detail. I fetched it and confirmed that absence rather than inferring one.**]

**Borrow.** The transferable idea is *one owned signature element* carrying the personality, with everything else kept plain — for us that signature is the emblem and the blue, not illustration. Also: "primary actions surfaced, supporting tools available" corroborates the one-bottom-anchored-action pattern the app already has [CODE `stage3_screen.dart:479-491`].

**Where it does NOT transfer.** Custom illustration is a commissioned asset the pilot does not have and cannot cheaply acquire; it would also add PNG weight to an app on a 2 GB device. And a *lifestyle* bank's confidence register is not a *mandated re-verification* register.

### 3.6 Bankak / بنكك (Bank of Khartoum) — the local comparison the audience will actually make

**What it is.** The dominant mobile banking app in Sudan. Its own listing documents recent versions including a *"Pre login screen new design"* and Arabic/English support [SOURCED — https://play.google.com/store/apps/details?id=com.mode.bok.ui; https://bankofkhartoum.com/sudan/digital/bankak/bankak-account].

**Why it matters more than any Gulf reference.** [INFERRED] Every pilot user, and every bank staff member in the pilot, has Bankak on their phone. Whatever "modern" means to this audience, it is calibrated against Bankak, not against Revolut. The comparison that decides the PO's reaction is a local one.

**Provenance, stated plainly.** **[UNVERIFIED]** I could not source any description of Bankak's colours, surfaces, gradients or motion. Design-community work tagged to Bank of Khartoum exists on Behance [SOURCED — https://www.behance.net/search/projects/bank%20of%20khartoum] but I could not extract specifics from it, and third-party portfolio work is not evidence of the shipped app. **I am not going to invent what it looks like.** This is an open question for the PO (§9, Q4), and it is the cheapest possible piece of research to close — the PO already has the app.

**I also attempted the project's own named UX benchmark** [PROJECT_PLAN.md line 140: "Benchmark UX against: https://mb1.sfbank-sd.com"]. [OBSERVED] Fetching it returns a client-rendered shell containing only the word "Pearl" — no markup, no CSS, no colour values. It is a JavaScript SPA and yields nothing to a text fetch. Recorded so nobody repeats the attempt.

### 3.7 Material 3 / Flutter platform documentation — the mechanism, not a look

**Borrow.** The `surfaceContainer*` role ladder is a ready-made, tonally-correct, seed-derived answer to "layered surfaces that lift off the background" that requires no hand-picked colours and therefore does not violate D2.1 [SOURCED — https://api.flutter.dev/flutter/material/ColorScheme-class.html; OBSERVED at `color_scheme.dart:435-443`]. And the expensive-operations list is the hard constraint on everything in §7 [SOURCED — https://docs.flutter.dev/perf/best-practices].

**Where it does NOT transfer.** M3's newer "expressive" direction — emphasised easing, container transform, morphing shapes — assumes hardware where those are affordable, and the prior research already ruled container transform out on API 28 [existing research §3.6]. M3 is also **silent on gradient direction under RTL**; you must handle it yourself (§6.3).

---

## 4. Splash — three motion directions

All three share five non-negotiables, so they are stated once rather than repeated:

- **Non-blocking.** `_navigate` stays fire-and-forget; the controller is never awaited and never a gate. D7.3. The existing test asserting navigation at ~120 ms into an 1800 ms timeline [reported at S8-03 §5] must keep passing unchanged.
- **Reduced motion.** `MediaQuery.disableAnimationsOf(context)` → jump to the finished composition [CODE lines 47–50]. Both tiers, always.
- **Continuous blue.** Native launch window and Flutter screen both `#105097`, no white flash. Not to be broken.
- **No letter-by-letter reveal, ever.** Mask/opacity only.
- **One controller.** The single-timeline discipline [CODE line 59–63] is correct and stays.

---

### P1 — "Lit field": the blue gets depth, the emblem gets light

**Reference.** Revolut's "surfaces lift cleanly" (§3.1); Mashreq Neo's brand-derived gradient (§3.4). Not copied from either — both are LTR-or-Gulf lifestyle products; what is taken is only *the field has dimension*.

**What it keeps from today.** Everything structural: the controller, the intervals, the lockup order, `FittedBox` on the wordmark, the `_sweep` on the slogan, `Expanded(5)/Expanded(2)`, the error path, the fire-and-forget navigation.

**What it replaces.** (a) the flat `Scaffold(backgroundColor: brandBlue)` becomes a `DecoratedBox` carrying a `RadialGradient` centred slightly above the emblem — a lighter blue at the centre falling to a deeper blue at the corners; (b) `_rule` is deleted (it is a hairline nobody sees) and its 32 px of vertical space redistributed; (c) the stock spinner is replaced by a 2 dp full-width brand rule at the bottom that pulses opacity, or a three-dot rest-state — decided at build, both are trivial.

**Concrete description.** From black screen the field appears at `#105097` flat. Over 0.0–0.6 s the radial centre brightens from flat to about 12–14% lighter than the base, as if a light came on behind the emblem; the emblem fades up (opacity 0→1) with a 0.94→1.0 scale over the same interval, so the light and the mark arrive together and the mark reads as *lit*, not as *pasted*. 0.20–0.55 s the wordmark rises 12 px and fades in. 0.42–0.78 s the slogan is revealed by the existing right-to-left mask. 0.70–1.0 s the tagline rises. The radial centre continues to drift upward by ~4% of screen height across the whole 1.8 s — slow enough to be felt, not watched. Nothing bounces. Nothing overshoots.

**Rich version.** Animated `RadialGradient` (`radius` and `center` both driven by the controller) + the emblem's scale/opacity + the mask sweep.

**Fallback version.** The radial gradient becomes **static** — computed once, painted once, never animated. It still supplies all the depth; only the "light coming on" is lost. Everything else runs identically.

**Tier boundary.** SDK_INT ≥ 29 → animated gradient. SDK_INT < 29 → static gradient. Detection in §8.

**RTL/Arabic.** A radial gradient centred horizontally is direction-neutral by construction — the same argument D6.1 used for `FadeUpwardsPageTransitionsBuilder`. No `AlignmentDirectional` needed as long as the centre stays on the horizontal midline. If the PO ever wants the light offset to one side, it becomes `AlignmentDirectional` and must be resolved (§6.3). No text reveal changes.

**Tone fit.** Strong. A light coming on behind an emblem is a calm, institutional gesture — closer to a lobby light than a product launch. It is the option least likely to read as playful.

**Flutter build complexity.** `AnimatedBuilder` → `DecoratedBox(decoration: BoxDecoration(gradient: RadialGradient(...)))`. A gradient fill draws directly to the surface; it is **not** on Flutter's `saveLayer` list [SOURCED — https://docs.flutter.dev/perf/best-practices]. Animating gradient *stops/radius* rebuilds the `BoxDecoration` and re-uploads the shader each frame — cheap, but it is real per-frame work and is why the fallback freezes it. Replace `Opacity` with `FadeTransition` while you are in the file (§2.1 item 4). Delete `_rule`, which removes a per-frame **relayout**. **Packages: none.** ~40 lines net.

**Effort:** small. **Risk:** low. **"Beats the current version" confidence:** high — it changes the thing that most reads as template (the flat field).

---

### P2 — "One light pass": the sweep becomes the whole idea ★ RECOMMENDED

**Reference.** Revolut's *one confident move* (§3.1). Explicitly **not** Revolut's tone: no scale-punch, no spring, no accent-colour flash.

**What it keeps.** The `_sweep` primitive and, crucially, its *reason* — mask reveal over correctly-shaped Arabic [CODE lines 195–204]. The controller, the lockup order, the intervals' relative ordering, the composition, fire-and-forget navigation, the error path.

**What it replaces.** The five independent fade-ins. Instead of each element having its own entrance, **one soft band of light travels once across the lockup from the reading start (right) to the end (left), and each element becomes visible as the light reaches it.** The wordmark's `_rise` and the tagline's `_rise` are replaced by presence-under-the-mask; the emblem still fades and scales, but now it is the *first thing the light touches* rather than a separate event.

**Concrete description.** Everything is laid out and fully shaped from frame one, at zero alpha. A vertical band of light — soft leading edge, ~14% of screen width, hard-ish trailing edge — starts just off the right edge at t=0 and finishes just off the left edge at t≈1.35 s, easing `easeInOutCubic` so it accelerates in and settles out. As it passes, the emblem, wordmark, slogan and tagline are each revealed. Because they sit at different vertical positions but the same horizontal band, the reveal reads as one continuous wipe, not four events. At t≈1.35 s the band leaves and everything is at full opacity. From 1.35–1.8 s only one thing moves: a 2 dp brand rule at the bottom drawing outward from centre — which is `_rule` promoted from a decorative hairline to the *settle beat*. The spinner appears there only if the launch decision is still pending.

**Rich version.** A single `ShaderMask` wrapping the whole lockup `Column`, `BlendMode.dstIn`, four-stop `LinearGradient` (transparent / white / white / transparent) whose stops are driven by the controller — one shader, one masked layer, one gesture.

**Fallback version — and this is the strongest fallback in the report.** Delete the `ShaderMask` entirely. Replace it with four staggered `FadeTransition`s on the *same* controller, with intervals set to the times at which the band would have reached each element. The **sequencing survives**; only the visible band is lost. There is **zero `saveLayer`** in the fallback, so it is strictly cheaper than what ships today (today's `_sweep` already costs one `saveLayer` per frame). The fallback is not a compromise version — it is a genuinely good splash in its own right.

**Tier boundary.** SDK_INT ≥ 29 → masked band. SDK_INT < 29 → staggered fades. Detection in §8.

**RTL/Arabic.** This is where P2 earns its recommendation. The band **must** be specified as `begin: AlignmentDirectional.centerStart, end: AlignmentDirectional.centerEnd` and resolved with `createShader(rect, textDirection: Directionality.of(context))`. [OBSERVED — `gradient.dart:423,438` declare `begin`/`end` as `AlignmentGeometry`; `gradient.dart:452-455` shows `createShader` calling `begin.resolve(textDirection)`.] Doing so removes the hardcode at [CODE line 217–222] rather than propagating it. Stops must stay monotonically ascending [OBSERVED — `gradient.dart:193-194`: *"The values in the `stops` list must be in ascending order"*] — the current code's `.clamp()` calls exist for exactly this reason and the same discipline applies to a four-stop band. No text is re-shaped at any frame: the glyphs are laid out once, and only their alpha changes.

**Tone fit.** Very strong. One slow, deliberate pass reads as *considered*. It is the opposite of playful because nothing is quick and nothing bounces.

**Flutter build complexity.** `AnimatedBuilder` + one `ShaderMask`. **Honest cost:** Flutter's docs name `ShaderMask` explicitly among *"Other widgets that might trigger `saveLayer()`"*, and `saveLayer()` *"allocates an offscreen buffer … triggers a render target switch on the GPU, which is particularly disruptive to rendering throughput"* [SOURCED — https://docs.flutter.dev/perf/best-practices]. Today's `_sweep` masks one short line of text; P2's mask covers the whole lockup, so **the offscreen buffer gets substantially larger**. That is a real cost increase on the legacy renderer, and it is exactly why the fallback removes the shader rather than shrinking it. Also: replace `Opacity` with `FadeTransition` throughout. **Packages: none.** ~60 lines net; the file gets *shorter* because four helpers collapse into one.

**Effort:** small–medium. **Risk:** low (the fallback is a complete, independently-good implementation). **"Beats the current version" confidence:** high.

---

### P3 — "Handoff": the blue field retracts to reveal the first screen

**Reference.** The idea that a launch screen should *become* the app rather than cut to it. [UNVERIFIED as a citation — I could not source a specific named app doing this that I could verify; I am presenting it on its own merits, not on borrowed authority. Do not let anyone record this as "how Revolut does it".]

**What it keeps.** The whole lockup and its motion — P3 is an *exit* treatment and composes with either P1 or P2 for the entrance.

**What it replaces.** The instant cut from splash to first screen. Today `_navigate` fires and the blue screen is gone in one frame.

**Concrete description.** The brand field is moved out of `LaunchScreen` and into an overlay above the Navigator, in `FruApp`'s existing `builder` [CODE `app.dart:27-28`], as a `Stack` layer. It is opaque `#105097` and covers everything, so the customer's experience at launch is unchanged. When the launch decision resolves, navigation happens **immediately and unconditionally** — the first screen builds and lays out *behind* the still-opaque overlay. One frame later the overlay retracts upward over ~360 ms, revealing the real screen underneath, whose header zone (if S3 is chosen) is the same blue and therefore appears to be *what the splash turned into*.

**The non-blocking property is preserved, and this is the part to check hardest.** Navigation is not delayed by a single frame. The overlay is pure decoration living above the Navigator; it cannot gate anything because it is not in the navigation path. [INFERRED — this is the design intent; a reviewer should verify against the built code that no `await` was introduced.]

**Rich version.** Overlay retracts with `Align(alignment: Alignment.topCenter, heightFactor: 1→0)` inside a `ClipRect`, `easeInOutCubic`, wrapped in `IgnorePointer`.

**Fallback version.** No retraction. The overlay is removed in one frame — i.e. exactly today's cut. Zero risk on the low tier because the low tier simply does not run it.

**Tier boundary.** Same SDK_INT ≥ 29 line.

**RTL/Arabic.** The retraction is **vertical**, which makes it direction-neutral by construction — the same principle D6.1 applied to page transitions. A horizontal retraction would be a genuine RTL hazard and should be refused.

**Tone fit.** Good but the most "designed" of the three. A vertical retraction at 360 ms with no bounce stays calm; anything faster or springier tips into product-launch energy.

**Flutter build complexity — and the honest problems.** `Stack` + `ClipRect` + `Align(heightFactor:)` + `AnimatedBuilder` + `IgnorePointer`. Clipping is **not** on the `saveLayer` path by default: *"clipping doesn't call `saveLayer()` unless explicitly requested with `Clip.antiAliasWithSaveLayer`"* [SOURCED — https://docs.flutter.dev/perf/best-practices] — so use the default `Clip.hardEdge` and never `antiAliasWithSaveLayer`. But the same page also says *"Avoid clipping in an animation"*, and this animates a clip for 360 ms. **Three real risks, named rather than smoothed over:**
1. If the first screen has not finished laying out when the retraction starts, the reveal exposes a half-built screen — worse than a cut. [INFERRED] Mitigation: gate the retraction on one post-frame callback after navigation, which costs one frame and still does not delay navigation.
2. The first screen after launch is not always the same screen — it can be any of eight destinations [CODE `_navigate`, lines 284–305], including the terminal screen and an error state. The reveal must look right for all of them, not just `/account-entry`.
3. Moving the brand field out of `LaunchScreen` and into `FruApp` touches the app shell, which is the file the S8-03 review specifically added `test/core/app_test.dart` to protect.

**Effort:** medium. **Risk:** medium — the only option here whose failure mode is *visibly worse* than today. **"Beats the current version" confidence:** high if it lands, but it is the one that can miss.

---

## 5. Surface — three treatment directions

---

### S1 — "Tinted canvas, white containers" ★ RECOMMENDED (this is the priority item)

**Reference.** Monzo's *"depth and section separation come entirely from alternating backgrounds rather than elevation"* (§3.2); M3's `surfaceContainer*` ladder (§3.7); corroborated by the UAE Design System's flat-UI position (§3.3).

**The idea in one sentence.** Push the *page* one tonal step **down** (a faintly brand-tinted off-white) and push the *controls* one tonal step **up** (white), so every field, every list tile and every card becomes a soft container floating on a tinted page — with no shadow, no gradient and no blur anywhere.

**Concrete specification.**

| Theme hook | Value | Effect, per screen, with zero screen edits |
|---|---|---|
| `scaffoldBackgroundColor` | `scheme.surfaceContainerLow` | The canvas of all 18 routes moves off `scheme.surface` |
| `inputDecorationTheme` | `InputDecorationThemeData(filled: true, fillColor: scheme.surfaceContainerLowest, border: OutlineInputBorder(borderRadius: 12, borderSide: none), focusedBorder: 1.5 dp `primary`, errorBorder: 1.5 dp `error`, contentPadding: 14/16)` | Every `TextField`, `DropdownButtonFormField` and `PickerField` on every screen becomes a white rounded container |
| `cardTheme` | `CardThemeData(color: surfaceContainerLowest, elevation: 0, shape: 16 dp radius, margin: zero)` | Stage 9's card, and anything added later |
| `listTileTheme` | `ListTileThemeData(tileColor: surfaceContainerLowest, shape: 12 dp radius)` | Every `RadioListTile` on Stage 3 and the education list becomes a selectable white row |
| `appBarTheme` | `AppBarTheme(backgroundColor: surfaceContainerLow, surfaceTintColor: transparent, elevation: 0, scrolledUnderElevation: 0.5)` | The bar stops being a separate slab; a faint separation appears only once content scrolls under it |
| `dividerTheme` | `DividerThemeData(color: scheme.outlineVariant, thickness: 1, space: 1)` | Consistent hairlines |

**All six values come from the seeded `ColorScheme`.** Nothing is hand-picked, which is what D2.1 requires. [OBSERVED — `color_scheme.dart:435-443` shows these roles populated from `MaterialDynamicColors`.] **[UNVERIFIED — the resolved hex values.]** They must be printed and eyeballed at build time. This is not paranoia: D3.2 records that `fromSeed`'s `primary` was assumed and turned out wrong, and that assumption is now a pinned override plus a test. Apply the same standard: assert in a widget test that `scaffoldBackgroundColor != scheme.surfaceContainerLowest`, so a future "simplification" cannot silently collapse the two tones back together and undo the whole treatment.

**Rich version and fallback version: identical.** There is nothing to tier. Flat fills cost the same on Impeller and on the legacy renderer, and there is no animation. **This is a genuine advantage and it is worth weighing heavily** — it is the only option in this report that cannot behave differently on the Huawei.

**RTL/Arabic correctness.** Colours have no direction. `OutlineInputBorder`'s radius is symmetric. `contentPadding` should be specified as `EdgeInsetsDirectional` so a future asymmetric value cannot flip. Text-scale 1.3×: filled fields grow with their content and the existing 16 px page padding is unchanged, so the S8-03 device result (no overflow at 1.3×) should hold — but it must be **re-checked**, because `filled: true` changes the intrinsic height of every field in the app.

**Tone fit.** Excellent. This is the calmest possible way to look modern: nothing shouts, nothing shines, containment does all the work. It reads as *organised*, which for a mixed-literacy audience filling forty fields is worth more than reading as *premium*.

**Flutter build complexity.** Pure `ThemeData`. **No `DecoratedBox`, no `ShaderMask`, no `CustomPainter`, no `BackdropFilter`, no gradient, no `saveLayer`, no `Transform`, no `AnimatedBuilder`.** ~25 lines in `app_theme.dart`. **Packages: none.**

**The one place this touches a component, stated honestly.** `filled: true` changes what a text field *looks like* — it goes from an underline to a filled rounded box. That is on the boundary of the out-of-scope "field redesign". My reading: it is inside scope because it is achieved entirely through `InputDecorationThemeData` with no screen file touched, no behaviour changed and no new widget. The PO should confirm. If they judge it out of scope, S1 still works with `filled: false` — you keep the tinted canvas and the white cards/tiles, and lose the field containment, which is maybe 60% of the value.

**Post-pilot dependencies (one line each, not designed here).** (a) The fixed 140 dp label row at `stage9_screen.dart:457-468` will look under-designed once everything around it is contained. (b) `OfflineBanner` will need a surface role assigned. (c) The bottom action `Padding` on every data-entry screen becomes the obvious next container — that is S2's territory.

**Effort:** small. **Risk:** very low. **Leverage:** highest in the report.

---

### S2 — "Alternating bands": the page is horizontal zones, not one field

**Reference.** Monzo, directly (§3.2). Also the closest thing to what the UAE Design System's flat-UI stance would produce (§3.3).

**The idea.** Rather than containers floating on a canvas, the *page* is divided into full-bleed horizontal bands of alternating tone: a tinted header zone (app bar + title), a white content band, and a distinct bottom action band carrying the primary button. Nothing floats; the structure is legible from the tone changes alone.

**Concrete description.** App bar and any offline banner sit on `surfaceContainer` (the darkest of the three). The scrolling content region is `surfaceContainerLowest` (white). The fixed bottom `Padding` holding the `FilledButton` — which already exists on every data-entry screen, outside the scroll view [CODE `stage3_screen.dart:479-491`] — becomes `surfaceContainerLow` with a 1 dp `outlineVariant` hairline along its top edge. The result reads as a real "action bar", which is the single largest perceived-quality jump available on a form screen: it tells the customer, without words, that the button is a fixed commitment point rather than another item in the list.

**The honest cost, and why this is ranked third.** The header band and content band are theme-level. **The bottom action band is not.** `ThemeData` has no hook for "the thing you put below your `Expanded`". Delivering S2's best part means wrapping that `Padding` in a `ColoredBox` + `Border` on each screen — a mechanical, one-line-per-screen edit across roughly 12–18 files. That is not a redesign and it is not risky, but it is not "zero screen edits" and the ask asked for zero. **If the PO wants only the theme half, S2 degrades into a weaker S1.**

**Rich / fallback: identical.** Flat fills, no tiering needed. Cheapest option in the report to render.

**RTL/Arabic.** Horizontal full-bleed bands are direction-neutral. The top hairline uses `Border(top:)` which is not directional. The only care needed is that any asymmetric inset uses `EdgeInsetsDirectional`.

**Tone fit.** Good, and the most *sober* of the three — arguably the best fit for a bank if the PO's complaint were only "it's unstructured". It is the weakest answer to "it looks like a template", because it adds no colour interest at all and a reviewer glancing at a screenshot may not register the change.

**Flutter build complexity.** `ThemeData` + `ColoredBox` + `Border` per screen. `ColoredBox` is the cheapest painting widget in the framework. No shader, no `saveLayer`, no gradient. **Packages: none.**

**Effort:** small theme half, medium for the full version (12–18 mechanical edits). **Risk:** very low. **Leverage:** medium.

---

### S3 — "Brand gradient header band": the blue extends into the app

**Reference.** Mashreq Neo's parent-brand-derived gradient (§3.4); Revolut's gradient-backed surfaces (§3.1). This is the option that most directly answers the PO's "subtle brand-tinted gradient" and it is the one the two settled Arabic-first sources are least enthusiastic about (§3.3).

**The idea.** The top ~150–180 dp of every screen is a **vertical** brand gradient — `#105097` at the top falling to a lighter tonal neighbour — carrying a transparent app bar and the page title in white. The content area below sits on S1's tinted canvas, and the first content container overlaps the bottom of the gradient by ~16 dp so it reads as sitting *in front of* the band rather than below it. The splash's blue field continues into the app instead of ending at it.

**How it reaches every screen with zero screen edits.** Put a top-aligned `SizedBox(height: X, child: DecoratedBox(gradient))` inside `FruApp`'s existing `builder`, **behind** the Navigator, and set `scaffoldBackgroundColor: Colors.transparent` and `appBarTheme.backgroundColor: Colors.transparent`, `foregroundColor: Colors.white`. Every route then renders on top of the band. The gradient is painted **once, behind the Navigator**, so it is not rebuilt or re-rasterised on navigation.

**The specific risk that makes this the runner-up rather than the recommendation.** [INFERRED — needs a device check, and I could not verify it from source this session.] With `scaffoldBackgroundColor: Colors.transparent`, both the outgoing and incoming route are transparent during a page transition. `FadeUpwardsPageTransitionsBuilder` animates the *incoming* route's opacity and slide while the outgoing route sits still underneath — so for the transition's duration the customer may see **the outgoing screen's Arabic text showing through the incoming screen's Arabic text**. On a densely-worded form screen that would look broken. Two mitigations, both real:
- **(a)** Give the gradient a fully opaque `surfaceContainerLow` continuation below the band, and keep `scaffoldBackgroundColor` transparent only where the band is. Does not fix it — the transparency is per-route, not per-region.
- **(b)** Keep scaffolds **opaque** and deliver the band per-screen instead, via a shared `BrandHeader` used as `Scaffold.appBar` (it can be a `PreferredSizeWidget` carrying its own gradient `flexibleSpace`). This costs one line per screen, ~18 files, and eliminates the risk entirely. **This is the version I would actually build**, and it means S3's honest cost is *not* zero screen edits.

**Why the gradient must be vertical.** [OBSERVED — `gradient.dart:423,438,452-455`] `begin`/`end` are `AlignmentGeometry` and are resolved through `createShader(rect, textDirection:)`. A **vertical** `topCenter → bottomCenter` gradient has zero horizontal component and is therefore direction-neutral *by construction* — structurally immune to RTL mirroring, which is precisely the argument D6.1 used to choose `FadeUpwardsPageTransitionsBuilder`, and the same argument should decide this. If the PO wants a diagonal, it becomes mandatory to use `AlignmentDirectional.topStart`/`bottomEnd` **and** pass `Directionality.of(context)` — otherwise the gradient runs the Latin way in an Arabic app and every test still passes. Note that a gradient inside a `BoxDecoration` is resolved via the ambient `ImageConfiguration`'s `textDirection` rather than an explicit argument; a gradient passed to a `ShaderMask`'s `shaderCallback` is **not**, and must be given the direction by hand. That asymmetry is where this gets got wrong.

**Rich version.** Three-stop vertical gradient with an eased midpoint, plus a very faint radial highlight behind the page title.

**Fallback version.** Two-stop linear gradient, no radial highlight. Essentially indistinguishable at a glance. **This is not much of a tier** — a linear gradient fill is cheap on both renderers because it is a direct draw, not a `saveLayer` [SOURCED — https://docs.flutter.dev/perf/best-practices lists `ShaderMask`, `ColorFilter`, `Chip` and overflow-shader `Text` as `saveLayer` triggers; a `BoxDecoration` gradient is not among them]. The real low-end cost is **first-frame shader compilation**, which happens once. See §7 for the design consequence.

**Tone fit.** The riskiest of the three on tone. A blue header band on a bank app is conventional and trustworthy; a *saturated, high-contrast* one starts to read as marketing. Keep the drop shallow (the lighter endpoint should be a tonal neighbour of `#105097`, derived from the scheme, not a different hue) and keep the band under ~180 dp. White-on-blue contrast is already known good: 8.03:1 [D2, computed at the prior research].

**Text-scale 1.3×.** The band must be a **background**, never a container that clips. If a wrapped two-line Arabic title grows past 180 dp, the title must overflow onto the canvas rather than be cut. `SizedBox`-with-`DecoratedBox` behind a freely-sized header achieves this; a `Container` wrapping the header does not.

**Flutter build complexity.** `DecoratedBox` + `BoxDecoration(gradient: LinearGradient)` + either `MaterialApp.builder` (zero screen edits, transition risk) or a shared `PreferredSizeWidget` with `flexibleSpace` (~18 one-line edits, no transition risk). No `saveLayer`, no blur, no `CustomPainter`. **Packages: none.**

**Effort:** medium. **Risk:** medium (the transparent-scaffold transition question is unresolved and must be checked on a device before committing to the zero-edit variant). **Leverage:** highest visual delta of the three, and the only one that visibly connects the splash to the app.

---

## 6. Ranking, pairing, and the RTL rule that spans both

### 6.1 Ranking

**Surface (the priority item):**
1. **S1** — highest leverage per unit of risk; zero screen edits; no tiering; no new primitives. **Recommended default.**
2. **S3** — biggest visual jump and the only one that continues the splash into the app, but carries one unresolved transition risk and a likely 18-file cost in its safe variant. **Additive on top of S1, not an alternative to it.**
3. **S2** — cheapest to render and the most sober, but its best element is not theme-level and it adds no colour interest, so it is the least likely to satisfy "stop looking like a template".

**Splash:**
1. **P2** — one idea, executed once; keeps and generalises the app's best existing motion primitive; fixes a latent RTL hardcode; has the cleanest fallback in the report. **Recommended default.**
2. **P1** — attacks the flat field, which is arguably the actual complaint; lower ceiling but also lower variance. Its radial-field element is **separable** and pairs with P2 for roughly ten extra lines, because it is the same `DecoratedBox`+gradient primitive S3 uses.
3. **P3** — the highest ceiling and the only option whose failure mode is worse than today. Worth doing *after* S3 exists, not before.

### 6.2 Which pairs are coherent

| Pair | Verdict |
|---|---|
| **P2 × S1** | **Coherent, and the recommended shipping pair.** Both are "one restrained idea, no ornament, no tiering surprises". Lowest total risk; clearly beats today. |
| **P1 × S3** | **Most coherent visually.** The splash's radial blue field and the header band are the same primitive and the same colour family — the blue literally continues from splash into every screen. Highest brand continuity. Higher effort and one open risk. |
| **P2 × S3** | Coherent. The light pass reads as brand motion; the band reads as brand surface. Slightly less unified than P1×S3 because the splash's *field* is still flat. |
| **P3 × S3** | **The most impressive, and the riskiest.** The retraction reveals the gradient header, so the splash appears to *become* the app. Do not attempt before S3 is on a device. |
| **P2 × S2** | Coherent but flat. Likely to be judged "cleaner, still a template". Not recommended as the answer to this brief. |
| **P1 × S1** | Fine. Mild disconnect — the splash has depth, the app is deliberately flat — but nobody will notice across a screen transition. |
| **P3 × S2** | **Incoherent.** A dramatic retraction revealing a flat banded form is an anticlimax; the motion writes a cheque the surface does not cash. |

### 6.3 The one RTL rule that spans both items

State it once, apply it everywhere:

> **Prefer direction-neutral geometry. Where direction is unavoidable, derive it — never hardcode it.**

Direction-neutral by construction: vertical gradients, horizontally-centred radial gradients, vertical retractions, opacity, uniform scale, symmetric radii. These are immune to RTL, not merely correct today. This is the same reasoning D6.1 used to pick `FadeUpwardsPageTransitionsBuilder` over a mirrored slide, and it is why that decision has never needed maintenance.

Where direction *is* the point (the P2 light pass, any diagonal gradient): [OBSERVED — `gradient.dart:423,438` and `452-455`] use `AlignmentDirectional` for `begin`/`end` and pass `textDirection: Directionality.of(context)` to `createShader`. `AlignmentGeometry.resolve(textDirection)` is what flips it. Keep `stops` monotonically ascending [OBSERVED — `gradient.dart:193-194`].

And the constraint that governs all of it: **no letter-by-letter reveal.** Mask and opacity only. The existing `_sweep` comment [CODE lines 195–204] is the canonical statement of why, and it should be moved verbatim onto whatever replaces it rather than rewritten.

---

## 7. Low-end performance: the constraint, and the design rule it generates

**The floor.** [SOURCED — https://docs.flutter.dev/perf/impeller, fetched this session] *"Impeller is available and enabled by default on Android API 29+. On devices running lower versions of Android or don't support Vulkan, Impeller falls back to the legacy OpenGL renderer. No action on your part is necessary for this fallback behavior."*

**The test device is below it.** [CODE `docs/sessions/2026-09-07-prepilot-mobile-ui.md` §2] Huawei AMN-LX9, Android 9 / API 28, 2 GB → legacy renderer, first-run shader compilation live. That report records `Skipped 776 frames` at first launch and `Skipped 67 frames` shortly after, on a **debug** build — and states plainly that this is *"not a clean measurement of the release build's motion"*. It also records that the PO **accepted the current splash as smooth on that device**, which is a useful calibration: the bar is not "flawless", it is "the PO does not see a stutter".

**What is expensive, from the platform's own documentation** [SOURCED — https://docs.flutter.dev/perf/best-practices, fetched this session]:
- `saveLayer()` *"is an expensive operation … allocates an offscreen buffer … triggers a render target switch on the GPU, which is particularly disruptive to rendering throughput"*.
- Widgets that may trigger it: **`ShaderMask`**, `ColorFilter`, `Chip` (when `disabledColorAlpha != 0xff`), `Text` with an `overflowShader`.
- *"Avoid using the `Opacity` widget, and particularly avoid it in an animation."* Prefer a semitransparent colour, or `AnimatedOpacity`/`FadeTransition`.
- *"Avoid clipping in an animation."* But clipping *"doesn't call `saveLayer()` unless explicitly requested with `Clip.antiAliasWithSaveLayer`"*.
- **Not on the expensive list at all: gradient fills.** A `BoxDecoration` gradient is a direct draw.

**What is banned outright, and stays banned.** `BackdropFilter` / `ImageFilter.blur` / glassmorphism; large-`blurRadius` `BoxShadow` on many simultaneous surfaces; mesh or animated multi-shader gradients; `Hero` on images; any vector-animation runtime. These come from the prior research's §2.4 banned list, which D6.4 *softened* to "outside the vocabulary" rather than prohibited — but every option in this report stays outside it anyway, so nothing is being reopened.

**The design rule this generates, and it is the reason S1+S3 compose well.** The prior research states it precisely [existing research §2.4]: *"the only practical mitigation is to use few distinct effects, and to reuse the same ones everywhere so they compile once and early … a small vocabulary is not a compromise here, it is the mitigation."*

Concretely: **if the splash and the surface treatment use the same primitives, the shader compiles once, during the splash, where a hitch is least visible and where the customer is already waiting for a real network call.** A gradient introduced *for the first time* on Stage 3 would compile mid-form. A gradient first drawn on the splash and then reused as the header band on every screen compiles once, at the moment the app already owns.

This is a real argument for pairing **P1 or P2 (which introduce a gradient primitive on the splash)** with **S3 (which reuses it)** — and it is a real argument that **S1 alone needs no tiering at all**, because a flat fill compiles nothing new.

---

## 8. Tier detection — where the boundary is and how to read it

**The boundary.** `Build.VERSION.SDK_INT >= 29` → rich. `< 29` → fallback. This is the Impeller threshold verbatim, so the tier boundary and the renderer boundary are the same line, which is the only defensible place to draw it.

**How to read it — three routes, evaluated against this project's constraints.**

1. **A ~15-line `MethodChannel` reading `Build.VERSION.SDK_INT` in `MainActivity.kt`.** ★ **Recommended.** Zero packages, therefore zero AD-006 evaluation, zero CLAUDE.md per-package iOS check, zero transitive weight, zero CocoaPods surface. On iOS the channel is simply not registered, the call fails, and the catch treats it as the rich tier — which is correct, since every supported iPhone runs Impeller. Read once at startup, cache in a provider. [INFERRED — the pattern is standard; the exact channel wiring should be confirmed against the pinned SDK when written.]
2. **`device_info_plus`.** Works and is well-maintained, but it is a package. It triggers CLAUDE.md's rule that iOS support be verified by artifact inspection at the moment of addition, and it pulls a plugin surface for one integer. **Not worth it** when the alternative is fifteen lines. Framework primitives are the stated preference and this is a clean case.
3. **Parsing `Platform.operatingSystemVersion`.** ✗ **Rejected on documentation.** [SOURCED — https://api.flutter.dev/flutter/dart-io/Platform/operatingSystemVersion.html] *"The format of this string will vary by operating system, platform and version and is not suitable for parsing."* An app that parses it will break silently on an OEM build. Recorded so nobody proposes it as the "no-plugin" shortcut — it is the shortcut, and it is wrong.

**What I could not find.** [UNVERIFIED — searched, not found] There is **no public Dart API to ask the engine whether Impeller is actually active**. Searching Flutter's docs, the Impeller page and the issue tracker surfaced only build-time and manifest-time controls (`--no-enable-impeller`, the `EnableImpeller` manifest meta-data), never a runtime query. So SDK_INT is a **proxy**, not a direct measurement, and it has one known false case: an API 29+ device that does not support Vulkan also falls back to legacy but would be classified rich. [INFERRED] That device would get the rich version on a legacy renderer — i.e. exactly today's situation, not a regression.

**Two signals that need no detection at all and must be honoured on both tiers:**
- `MediaQuery.disableAnimationsOf(context)` → jump to the final composition. Already implemented [CODE lines 47–50]; required by the UAE Design System's accessibility guidance [SOURCED — designsystem.gov.ae/guidelines/mobile-applications].
- Fire-and-forget navigation. D7.3.

**An adaptive alternative I considered and am rejecting.** `SchedulerBinding.addTimingsCallback` could measure real frame times and downgrade. It does not help: by the time enough frames have been measured, the splash is over, and the surface treatment is static so there is nothing to downgrade. It would add complexity and a source of nondeterminism to widget tests for no benefit. Named so it is not re-proposed.

---

## 9. What a mid-range emulator or preview tool CAN and CANNOT show

**CAN show — trust these:**
- **Layout, at every text scale.** Whether S1's `filled: true` fields overflow at 1.3×, whether S3's header band clips a wrapped Arabic title, whether the splash lockup still fits at 1.3× on a 5-inch form factor. Use Flutter's device-preview / `MediaQuery` overrides, and check 1.0× and 1.3× as the S8-03 pass did.
- **Colour and tone relationships.** Whether `surfaceContainerLow` vs `surfaceContainerLowest` is a visible step or an invisible one. This is a *rendering-independent* question and an emulator answers it perfectly. (Caveat: a cheap physical LCD in Sudanese daylight will show less tonal separation than a desktop monitor — which argues for choosing the *larger* of two candidate steps.)
- **RTL correctness.** Whether a gradient runs the right way, whether the P2 band travels right-to-left, whether padding mirrors. All deterministic, all visible in a widget test golden or an emulator.
- **Motion shape and timing.** Whether 1.35 s feels slow enough to read as calm and not so slow it reads as stuck. Whether the settle has an unwanted bounce.
- **Correct behaviour under "remove animations".** Toggle the emulator's developer setting and confirm the final composition appears instantly.
- **`saveLayer` counts.** DevTools' `checkerboardOffscreenLayers` works on an emulator and will show exactly how many offscreen layers P2's `ShaderMask` costs [SOURCED — https://docs.flutter.dev/perf/best-practices names this switch]. The *count* transfers even though the *cost* does not.

**CANNOT show — do not accept emulator or modern-phone evidence for these:**
- **Legacy-renderer shader-compilation jank.** An x86_64 emulator on API 33+ runs Impeller and will never reproduce it. The prior research states this flatly: *"a newer test phone will not reproduce the jank, so any motion work must be measured on the Android 9 device or it is not measured at all"* [existing research §1.7]. This is the whole reason the fallback tier exists.
- **Whether the fallback is actually needed.** It is possible the rich version is fine on the Huawei. Nobody knows, and the PO declined performance measurement on that device [D6(a)]. **Build the fallback anyway** — it is cheap in every option here, and the alternative is discovering the answer in front of bank staff.
- **`saveLayer` *cost*.** DevTools shows the count on any device; only the Huawei shows what a render-target switch costs on that GPU.
- **The S3 transparent-scaffold transition question.** Whether outgoing and incoming Arabic text visibly overlap during `FadeUpwards` is a *rendering* question and needs a real run — though an emulator will likely reveal it, since it is a compositing behaviour rather than a performance one. Check the emulator first; confirm on the Huawei.
- **Real-world legibility.** Tonal separation on a 5-inch 720p LCD in daylight. Only the device.
- **First-launch cold-start feel.** The `Skipped 776 frames` figure came from a debug build; a release build on the same handset is the only honest measurement, and it has not been taken.

**The minimum honest validation plan.** Emulator for layout, colour, RTL and motion shape → release build on the Huawei for the fallback tier and cold start → a single mid-range Android 11+ device for the rich tier. The middle device is the one this project does not currently have, and its absence is worth naming (§9, Q5).

---

## 10. What I could not determine

- **What Bankak / بنكك actually looks like.** No sourceable description of its colours, surfaces, gradients or motion. It is the app this audience compares against and I would not guess at it. [UNVERIFIED]
- **The bank's own web visual language.** `mb1.sfbank-sd.com` is a JS SPA; a text fetch returns the single word "Pearl" and no CSS. [OBSERVED] The PROJECT_PLAN names it as the UX benchmark; it cannot be read this way.
- **The resolved hex values of `surfaceContainerLow` / `surfaceContainerLowest` for this seed.** They are computed by `MaterialDynamicColors` at runtime [OBSERVED — `color_scheme.dart:435-443`]. I did not evaluate them. D3.2's precedent says print them, do not assume them. [UNVERIFIED]
- **Whether transparent scaffolds visibly break `FadeUpwardsPageTransitionsBuilder`.** I reasoned it from the transition's structure but did not verify against `page_transitions_builder.dart` or a run. It is the single most load-bearing unverified claim in the S3 section. [INFERRED / UNVERIFIED]
- **Whether the rich tier is smooth on the Huawei.** Unmeasured, and the PO declined to measure [D6(a)].
- **Any runtime Impeller-detection API.** Searched; not found. Absence of evidence, marked as such. [UNVERIFIED]
- **Revolut's, Mashreq Neo's and Monzo's actual current app surfaces.** Every visual claim about all three is from secondary write-ups and design-system scrapes, not observation. Cited as such throughout, and the specific hex values circulating for Monzo should not be copied into anything.

---

## 11. Risks, and what reversal costs

| Risk | If it fires | Cost to reverse |
|---|---|---|
| **S1's tonal step is invisible on a cheap LCD in daylight** | The flagship change delivers nothing visible; the app still reads flat | One value. Move the canvas from `surfaceContainerLow` to `surfaceContainer`. Minutes. |
| **`filled: true` is judged out of scope** (it changes how a field looks) | The field containment — most of S1's value — is lost | One boolean. S1 still ships with tinted canvas + white cards/tiles. |
| **`filled: true` overflows at text scale 1.3×** | An accessibility regression the S8-03 pass explicitly cleared | Reduce `contentPadding`, or revert the boolean. Small, but **must be tested** — this is the one place S1 can regress something already proven. |
| **S3's transparent scaffolds show text-through-text on transition** | Looks broken on every navigation — worse than shipping nothing | Switch to the per-screen `BrandHeader` variant: ~18 one-line edits. Not a redesign, but not free either. **Check this before choosing the zero-edit variant, not after.** |
| **P2's whole-lockup `ShaderMask` janks on the Huawei** | The splash — the PO's showcase item — stutters on the one device that matters for the demo | The fallback already exists and is a complete implementation. Flip the tier boundary to always-fallback: one condition. **This is why P2's fallback is a full splash and not a degraded one.** |
| **P3's reveal exposes a half-built first screen** | Visibly worse than today's clean cut | Delete the overlay; return to the cut. But the brand field has by then moved into `FruApp`, so the revert touches the app shell and the test guarding it. Medium. |
| **The PO reacts to a gradient as "not a bank"** | Wasted effort on S3 | S3 is additive on S1 and can be removed by deleting one `builder` layer. S1 survives. **This is the structural argument for building S1 first and S3 second, in that order.** |
| **The whole treatment contradicts the UAE Design System's flat-UI position** (§3.3) | Not a functional risk; a defensibility one if the bank later adopts a Gulf-style standard | S1 and S2 are *aligned* with flat UI. Only S3 diverges, and only in a header band. Low. |

**The reversal property that decides the ranking.** S1 is one file and no screen edits — reverting it is `git revert` on a single commit with no other consequence. S3's zero-edit variant is also one file, but its safe variant is 18 files. P3 touches the app shell. **Build in ascending order of reversal cost**, which is also ascending order of visual ambition: S1 → P2 → S3 → P3.

---

## 12. Open questions for the PO

1. **Does `filled: true` on every text field count as the out-of-scope "field redesign"?** My reading is no — it is achieved purely through `InputDecorationThemeData`, touches no screen file and changes no behaviour. But it is the one place S1 changes how a component looks, and the PO owns that line. **This is the single question that most changes S1's value.**
2. **Gradient, or tonal step?** S1 delivers "modern surface" with no gradient at all, and the project's own Arabic-first reference explicitly prefers that (§3.3). S3 delivers the gradient the brief asked for, at higher risk. **My recommendation is S1 first, then decide on S3 having seen S1 on a device** — the PO may find S1 is already the change they wanted.
3. **Is the splash's flat blue field the actual complaint?** If "not modern enough" means *the field is a plain colour*, P1 is the direct answer and P2 is not. If it means *the motion is fussy and unmemorable*, P2 is the answer and P1 is not. The two diagnoses point to different options and I cannot distinguish them from the brief. If the PO cannot say either, **P2 plus P1's static radial field** covers both for about ten extra lines.
4. **What does Bankak look like?** The PO has it on their phone. Five minutes with it is worth more than every secondary source in §3, because it is what this audience calibrates "modern bank app" against. Specifically: does it use a gradient header? Are its form fields filled or underlined? Is its canvas white or tinted?
5. **Is there a mid-range Android 11+ device available for the pilot check?** The current test device is *below* the design floor and there is nothing *at* it. Without one, the rich tier ships unvalidated on the hardware it was designed for — which is a different and larger gap than the fallback tier being unvalidated.
6. **Is the pilot's first impression a cold start or a warm one?** P3's whole value is the launch-to-app handoff. If bank staff will mostly be resuming an already-warm app, P3 is worth much less than it looks.

---

## 13. Card updates

There is no `docs/components/` card covering mobile theming or motion — the closest is `docs/components/mobile-packages.md`, which owns the "RTL — the LTR islands" rule. Nothing in this report changes that card.

**No SDK was investigated, so no new SDK card is owed.** If the PO picks any option, the natural home for the resulting rules is a new `docs/components/mobile-visual-system.md`, and the three lines it should open with are:

- `[OBSERVED — theme_data.dart:453]` The Scaffold canvas is `colorScheme.surface` unless `scaffoldBackgroundColor` is set. Because M3's `Card` defaults to `surfaceContainerLow`, the canvas and containers are adjacent tones by default and nothing lifts. Setting the canvas to `surfaceContainerLow` and containers to `surfaceContainerLowest` is what creates containment, and it is free.
- `[OBSERVED — gradient.dart:423,438,452-455]` `LinearGradient.begin`/`end` are `AlignmentGeometry`; `createShader(rect, textDirection:)` resolves them. A gradient in a `BoxDecoration` receives direction from the ambient `ImageConfiguration`; a gradient built inside a `ShaderMask.shaderCallback` does **not** and must be given `Directionality.of(context)` explicitly. Prefer vertical/radial-centred geometry, which is direction-neutral by construction.
- `[SOURCED — docs.flutter.dev/perf/best-practices]` `ShaderMask` is on Flutter's `saveLayer` list; a `BoxDecoration` gradient is not; clipping is not unless `Clip.antiAliasWithSaveLayer`. Any low-end fallback should remove the `ShaderMask`, not shrink it.

**One correction to an existing record, not a new finding:** `docs/sessions/2026-09-07-prepilot-mobile-ui.md` §2 marks the splash "PASS, PO-accepted" alongside `Skipped 776 frames`. Both are true and the report says so. Nothing needs correcting — noted only so a future session does not read the frame figure as contradicting the acceptance.

---

## 14. Noticed in passing

Not expanded, not acted on, listed once:

- **`_rule` animates `Container(width: 44 * t)`** [CODE line 235–239] — a per-frame **layout** pass for a 44 px hairline. `Transform.scale` on a fixed-width box, or `Align(widthFactor:)`, would keep it on the paint path. Trivial, and every splash option here either deletes or repurposes it anyway.
- **`_rise` and `_logo` use the `Opacity` widget in an animation**, against Flutter's explicit guidance [SOURCED — docs.flutter.dev/perf/best-practices]. `FadeTransition` is the drop-in. Free to fix while the file is open.
- **`account_entry_screen.dart:120` and `:187` build a non-const `Text` with an unused `error`/`stackTrace` parameter pair** — the string is a constant. Cosmetic; `flutter analyze` is clean so nothing is broken.
- **The app has exactly one `Card`** (`stage9_screen.dart:369`). Whichever surface option is chosen, that card will be the only pre-existing element that already agrees with the new language — worth glancing at first when checking the result.agentId: a399833a8e8a7d675 (use SendMessage with to: 'a399833a8e8a7d675', summary: '<5-10 word recap>' to continue this agent)
<usage>subagent_tokens: 160928
tool_uses: 43
duration_ms: 880463</usage>