import 'package:flutter/material.dart';

/// The app's single visual definition — brand colour, Arabic typography and page transitions.
/// Decisions D2, D3 and D6 of docs/sessions/2026-09-07-research-ui-ux-design-plan.md.
abstract final class AppTheme {
  /// The single brand ground — **navy `#0b1c47`, from the Design_3 handoff (AD-012 fork 1,
  /// product-owner ruling 2026-09-12)**, replacing D2.1's `#105097` as BOTH the seed and the
  /// pinned `primary`.
  ///
  /// **Reseeding, not merely repainting the splash, was the ruling.** The alternative on the
  /// table was to use navy only on the splash and banner and leave the Material ramp on the old
  /// blue; that was rejected because the two are a shade apart and would have met at the banner's
  /// own edge, reading as an off-colour rather than as a second brand tone. Everything derives
  /// from this: buttons, focus rings, selection, the tinted canvas.
  ///
  /// **The cost the ruling accepted is that every derived tone MOVED**, so the WCAG 1.4.11
  /// measurements D3 took on the old ramp do not carry over. They are recomputed rather than
  /// re-asserted — `app_theme_test.dart` resolves the live scheme and computes the ratios, so
  /// this constant cannot be changed again without the contrast claims being re-checked.
  ///
  /// The same value is declared ONCE for Android, in `res/values/colors.xml`, and referenced
  /// from there by both `launch_background.xml` variants, `values-v31/styles.xml`, the adaptive
  /// icon and `values/styles.xml`. Those two declarations — this one and the XML one — must not
  /// drift, or the native-to-Flutter handoff flashes.
  static const Color brandNavy = Color(0xFF0B1C47);

  /// Design_3's steel accent — contours, the pearl ring and the banner's glow (AD-012).
  ///
  /// **Not a `ColorScheme` role, deliberately.** It is decoration on the two brand surfaces, not
  /// a semantic colour, and promoting it to `secondary` would let it leak onto controls the
  /// handoff never designed. `BrandBanner` and `LaunchScreen` are its only consumers.
  static const Color brandSteel = Color(0xFF5980A6);

  static ThemeData light() {
    // D3.2: `fromSeed` maps the seed through a tonal palette and returns a LIGHTER, less
    // saturated `primary` — seeding with #0b1c47 does NOT give back #0b1c47. Seed for a
    // coherent palette, then pin `primary`/`onPrimary` explicitly, or "we set the brand
    // colour" silently becomes "we set something adjacent to it".
    final scheme = ColorScheme.fromSeed(seedColor: brandNavy).copyWith(
      primary: brandNavy,
      onPrimary: Colors.white,
    );

    return ThemeData(
      useMaterial3: true,
      // D3.4: light only for the pilot. No `darkTheme`, no `themeMode` — and the Android
      // `values-night` launch theme is made identical to `values`, so the OS's dark setting
      // cannot put a dark window behind a light app.
      brightness: Brightness.light,
      colorScheme: scheme,
      fontFamily: _fontFamily,
      // D3.3: anything outside the bundled subset's Unicode ranges falls back to the device
      // font rather than to tofu. Customer names come from the Uqudo scan and the Civil
      // Registry, so they can contain characters no literal in this repo contains.
      fontFamilyFallback: const ['sans-serif'],
      textTheme: _textTheme,
      pageTransitionsTheme: _pageTransitions,
      // S1 "tinted canvas, white containers" — docs/sessions/2026-09-07-research-splash-and-surface.md
      // §5, and the fix for walk bundle W-1 ("no visual hierarchy anywhere", five separate PO
      // comments with one cause). Before this, NONE of these slots were set: the canvas fell to
      // `colorScheme.surface` and cards to `surfaceContainerLow` by SDK default, which are adjacent
      // tones, so no container ever separated from the page behind it.
      //
      // The page moves DOWN the tonal ladder and the containers sit at the top of it, which is the
      // whole mechanism — depth comes from the tonal gap, never from elevation. Flat fills only: no
      // gradient, no shadow, no blur. That is also why this needs no device tiering, unlike the
      // splash — a flat fill costs the same on Impeller and on the legacy renderer, so a judgement
      // made on the API-28 pilot handset transfers to every device.
      //
      // **`surfaceContainerHigh`, not the research's `surfaceContainerLow`** — a deliberate
      // product-owner call on 2026-09-07 to err toward VISIBLE separation. `surfaceContainerLow`
      // sits roughly 3% off white, which reads as "slightly off" rather than as a container
      // sitting on a page; the point of the treatment is that a field obviously lifts. The tone
      // stays brand-tinted rather than neutral grey because the whole ladder is seeded from
      // #0b1c47. If it ever reads dirty rather than clean on a device, step DOWN this ladder
      // (`surfaceContainer`), do not desaturate — desaturating is what makes it grey.
      //
      // Every value is a role off the seeded `ColorScheme`, never a hand-picked hex — same rule as
      // D2.1. The one guard worth having is a test that the two tones cannot collapse back
      // together; see `app_theme_test.dart`.
      scaffoldBackgroundColor: scheme.surfaceContainerHigh,
      inputDecorationTheme: _inputDecoration(scheme),
      cardTheme: _cardTheme(scheme),
      listTileTheme: _listTileTheme(scheme),
      appBarTheme: _appBarTheme(scheme),
      dividerTheme: _dividerTheme(scheme),
      segmentedButtonTheme: _segmentedButtonTheme(scheme),
    );
  }

  /// Every `TextField`, `DropdownButtonFormField` and picker on every screen becomes a white
  /// rounded container on the tinted canvas — reached entirely through the theme, with no screen
  /// edit.
  ///
  /// `contentPadding` is `EdgeInsetsDirectional` rather than `EdgeInsets` deliberately: the values
  /// are symmetric today, so nothing changes now, but a future asymmetric value would silently pad
  /// the wrong side of an RTL field. Direction-neutral by construction beats corrected-for-today —
  /// the same argument D6.1 makes for the page transition.
  ///
  /// **The 1 dp `outline` hairline on the resting state is an ACCESSIBILITY requirement, not
  /// decoration, and the research's `borderSide: none` is wrong on this one point.** Measured on
  /// the resolved palette: the canvas (#E7E8EE) against a white field is 1.22:1. WCAG 1.4.11 wants
  /// 3:1 for the visual information that identifies a control — and NO tone on M3's light ladder
  /// can reach it (`surfaceContainerHighest`, the darkest, is 1.29:1), so a fill difference alone
  /// can never identify a field boundary no matter how far the canvas is dialled. `outline`
  /// (#74777F) measures 3.66:1 against the canvas and 4.48:1 against the field's own white fill,
  /// clearing 3:1 on both sides. It costs nothing visually — still flat, still one hairline — and
  /// it is what lets the tint be chosen for looks rather than for compliance.
  static InputDecorationThemeData _inputDecoration(ColorScheme scheme) {
    OutlineInputBorder border(Color color, double width) => OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: width == 0 ? BorderSide.none : BorderSide(color: color, width: width),
    );
    return InputDecorationThemeData(
      filled: true,
      fillColor: scheme.surfaceContainerLowest,
      contentPadding: const EdgeInsetsDirectional.symmetric(horizontal: 16, vertical: 14),
      border: border(scheme.outline, 1),
      enabledBorder: border(scheme.outline, 1),
      focusedBorder: border(scheme.primary, 1.5),
      errorBorder: border(scheme.error, 1.5),
      focusedErrorBorder: border(scheme.error, 1.5),
    );
  }

  /// The fill for an OPEN `DropdownButtonFormField` menu, which is the one surface in this app
  /// that **cannot** be reached through the theme at all.
  ///
  /// Material has no `DropdownButtonTheme`. `dropdown.dart` paints the menu with
  /// `widget.dropdownColor ?? Theme.of(context).canvasColor`, and `ThemeData` defaults
  /// `canvasColor` to `colorScheme.surface` — #F9F9FF, which is 1.17:1 against this app's
  /// #E7E8EE canvas. So while the dropdown's CLOSED state is a white container like every other
  /// field (via `inputDecorationTheme`), opening it revealed a menu on a different, near-canvas
  /// tone: the closed and open states of one control disagreeing about the surface language.
  ///
  /// Setting `canvasColor` globally would fix it in one line and is deliberately NOT done —
  /// `canvasColor` backs other Material surfaces too, so a global change to repair the dropdown
  /// would silently move things nothing here has looked at. Passed per site instead.
  ///
  /// **What this does not claim.** The menu was never invisible: `dropdown.dart` defaults it to
  /// `elevation: 8`, so it floats on a shadow and is perfectly findable. This is a consistency
  /// fix, not a legibility rescue. (That shadow is also the only surface in the app still
  /// contradicting "depth comes from tone, never elevation" — left alone, since suppressing it
  /// would make an unanchored popup harder to read, which is the one place elevation earns its
  /// keep.)
  static Color dropdownMenuSurface(ColorScheme scheme) => scheme.surfaceContainerLowest;

  /// `elevation: 0` because the tonal gap is doing the separating. A shadow on top of it would be
  /// the same idea said twice, and shadows are the expensive half on the legacy renderer.
  static CardThemeData _cardTheme(ColorScheme scheme) {
    return CardThemeData(
      color: scheme.surfaceContainerLowest,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    );
  }

  /// The signature screen's draw-versus-upload chooser (walk comment 12a) is the app's only
  /// `SegmentedButton`, and it was the one batch-one component reaching this theme through
  /// nothing at all.
  ///
  /// **Stated precisely, because "it inherits none of the surface language" would overstate it.**
  /// M3's own defaults already agree with two thirds of this theme: `segmented_button.dart`
  /// resolves `side` to `BorderSide(color: colorScheme.outline)` — the SAME 3.66:1 hairline role
  /// the input fields use — and `elevation: 0`, which is already "depth from tone, never
  /// elevation". The single divergence is the UNSELECTED segment's background, which M3 leaves
  /// null: transparent, so the tinted canvas shows through a control that every other resting
  /// interactive surface in this app renders as a white container. That is the whole fix here.
  ///
  /// Selected stays `secondaryContainer`, M3's default — against a now-white unselected segment
  /// it reads as the chosen one without a second mechanism being invented for it.
  static SegmentedButtonThemeData _segmentedButtonTheme(ColorScheme scheme) {
    return SegmentedButtonThemeData(
      style: ButtonStyle(
        backgroundColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return null;
          return states.contains(WidgetState.selected)
              ? scheme.secondaryContainer
              : scheme.surfaceContainerLowest;
        }),
      ),
    );
  }

  static ListTileThemeData _listTileTheme(ColorScheme scheme) {
    return ListTileThemeData(
      tileColor: scheme.surfaceContainerLowest,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    );
  }

  /// The bar stops being a separate slab and joins the canvas; a faint separation appears only
  /// once content actually scrolls under it. `surfaceTintColor: transparent` is required as well as
  /// the background — M3 tints an app bar by elevation overlay, which would re-introduce the very
  /// tone difference this removes.
  static AppBarThemeData _appBarTheme(ColorScheme scheme) {
    return AppBarThemeData(
      backgroundColor: scheme.surfaceContainerHigh,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0.5,
    );
  }

  static DividerThemeData _dividerTheme(ColorScheme scheme) {
    return DividerThemeData(color: scheme.outlineVariant, thickness: 1, space: 1);
  }

  static const String _fontFamily = 'IBMPlexSansArabic';

  /// AD-012 fork 2 — display and headline roles only. See `_textTheme`.
  static const String _displayFamily = 'Amiri';

  /// D6.1. Left unset, Flutter resolves Android to `PredictiveBackPageTransitionsBuilder`,
  /// which — with no manifest opt-in, and on anything below Android 13 — falls back to
  /// `FadeForwardsPageTransitionsBuilder`: a 450 ms horizontal ±0.25 slide whose LTR semantics
  /// are hardcoded ("the previous page slides from right to left as the current page appears").
  /// NO built-in Material page transition mirrors for RTL, so this Arabic app has been
  /// animating every forward navigation in the Latin direction — a wrongness, not a stutter,
  /// invisible because nothing tests for it.
  ///
  /// `FadeUpwardsPageTransitionsBuilder`'s tween is `Offset(0.0, 0.25) -> Offset.zero`. **x is
  /// zero**, so the motion is purely vertical and direction-neutral BY CONSTRUCTION — immune to
  /// the framework never mirroring, rather than merely corrected for it today.
  ///
  /// Any future BESPOKE transition must pass `textDirection: Directionality.of(context)` to
  /// `SlideTransition`, or it slides the wrong way under RTL with every test still green.
  /// Android only, exactly as D6.1 specifies. Every other platform keeps its own default —
  /// iOS's is already direction-correct and iOS is out of scope for this pilot, so naming it
  /// here would be an untested claim rather than a fix.
  static const PageTransitionsTheme _pageTransitions = PageTransitionsTheme(
    builders: {TargetPlatform.android: FadeUpwardsPageTransitionsBuilder()},
  );

  /// D3: `letterSpacing: 0` everywhere — Material's Latin-tuned POSITIVE tracking visually
  /// breaks the joins of a connected script, which is the single most damaging default for
  /// Arabic. `height: 1.6` on body for the taller ascender/descender range. Body 16 sp minimum,
  /// nothing customer-facing below 14 sp, nothing below weight 400, no italic.
  /// **AD-012 fork 2: Amiri carries the DISPLAY and HEADLINE roles and nothing else.** The
  /// Design_3 handoff sets the splash name in Amiri at 62 pt, where a Naskh serif is doing real
  /// display work. It has no matched Latin, and nearly every screen below a heading sets an
  /// Arabic label beside Latin digits — an account number, an `SFB-` reference, a scanned name —
  /// which is the exact defect D3.1 chose IBM Plex Sans Arabic to avoid. `fontFamily` above
  /// therefore stays IBM Plex Sans Arabic and only these six roles override it.
  ///
  /// `titleLarge`/`titleMedium`/`titleSmall` are deliberately NOT Amiri: they label sections
  /// INSIDE a screen, sit inches from field values, and several of them sit directly above a
  /// reference number.
  static const TextTheme _textTheme = TextTheme(
    displayLarge: TextStyle(fontFamily: _displayFamily, letterSpacing: 0, height: 1.3),
    displayMedium: TextStyle(fontFamily: _displayFamily, letterSpacing: 0, height: 1.3),
    displaySmall: TextStyle(fontFamily: _displayFamily, letterSpacing: 0, height: 1.3),
    headlineLarge: TextStyle(fontFamily: _displayFamily, letterSpacing: 0, height: 1.4),
    headlineMedium: TextStyle(fontFamily: _displayFamily, letterSpacing: 0, height: 1.4),
    headlineSmall: TextStyle(fontFamily: _displayFamily, letterSpacing: 0, height: 1.4),
    titleLarge: TextStyle(letterSpacing: 0, height: 1.4, fontWeight: FontWeight.w600),
    titleMedium: TextStyle(letterSpacing: 0, height: 1.5, fontWeight: FontWeight.w600),
    titleSmall: TextStyle(letterSpacing: 0, height: 1.5, fontWeight: FontWeight.w600),
    bodyLarge: TextStyle(fontSize: 16, letterSpacing: 0, height: 1.6),
    bodyMedium: TextStyle(fontSize: 16, letterSpacing: 0, height: 1.6),
    bodySmall: TextStyle(fontSize: 14, letterSpacing: 0, height: 1.6),
    labelLarge: TextStyle(fontSize: 16, letterSpacing: 0, height: 1.5),
    labelMedium: TextStyle(fontSize: 14, letterSpacing: 0, height: 1.5),
    labelSmall: TextStyle(fontSize: 14, letterSpacing: 0, height: 1.5),
  );
}
