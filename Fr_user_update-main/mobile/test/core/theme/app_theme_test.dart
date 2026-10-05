import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/theme/app_theme.dart';

/// WCAG 2.1 relative luminance, so the contrast claims below are computed rather than asserted
/// from a comment that can rot when the palette moves.
double _relativeLuminance(Color c) {
  double channel(double v) =>
      v <= 0.03928 ? v / 12.92 : math.pow((v + 0.055) / 1.055, 2.4).toDouble();
  return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
}

double _contrastRatio(Color a, Color b) {
  final la = _relativeLuminance(a);
  final lb = _relativeLuminance(b);
  return (math.max(la, lb) + 0.05) / (math.min(la, lb) + 0.05);
}

void main() {
  group('AppTheme — brand colour', () {
    test('primary is EXACTLY the brand navy, not the seeded tonal neighbour', () {
      // D3.2, and the whole reason `copyWith` is there. `ColorScheme.fromSeed` maps its seed
      // through a Material 3 tonal palette and returns a LIGHTER, less saturated primary — so
      // seeding with #0b1c47 does not give #0b1c47 back. Without the explicit pin, "we set the
      // brand colour" silently becomes "we set something adjacent to it", which is invisible in
      // review and wrong on every screen.
      //
      // AD-012 fork 1 (2026-09-12) moved this from D2.1's measured #105097 to the Design_3 navy.
      // The value is duplicated in `android/app/src/main/res/values/colors.xml`, which the
      // native launch window and the adaptive icon both read.
      expect(AppTheme.light().colorScheme.primary, const Color(0xFF0B1C47));
      expect(AppTheme.brandNavy, const Color(0xFF0B1C47));
    });

    test('the seed alone would NOT have produced it — the pin is doing real work', () {
      // Guards the pin against a future "simplification" that drops copyWith on the assumption
      // that fromSeed already returns the seed. If Flutter ever changes so that it does, this
      // fails and tells the next reader the pin is now redundant, rather than silently rotting.
      final seededOnly = ColorScheme.fromSeed(seedColor: AppTheme.brandNavy);
      expect(seededOnly.primary, isNot(AppTheme.brandNavy));
    });

    test('onPrimary is white, and the contrast is MEASURED rather than asserted', () {
      expect(AppTheme.light().colorScheme.onPrimary, Colors.white);
      // AD-012 fork 1 accepted that reseeding moves every derived tone, so D2.1's 8.03:1 claim
      // for the old blue does not carry over. Computed here instead of quoted: navy is darker,
      // so this can only have improved — but "can only have improved" is a guess until the
      // number is taken.
      final ratio = _contrastRatio(Colors.white, AppTheme.brandNavy);
      expect(
        ratio,
        greaterThanOrEqualTo(7.0),
        reason: 'WCAG AAA for body text on the primary fill; measured ${ratio.toStringAsFixed(2)}:1',
      );
    });

    test('the steel accent is NOT a ColorScheme role', () {
      // AD-012: steel is decoration on the splash and the banner, not a semantic colour.
      // Promoting it to `secondary` would let it leak onto controls the handoff never designed.
      final scheme = AppTheme.light();
      expect(scheme.colorScheme.secondary, isNot(AppTheme.brandSteel));
      expect(scheme.colorScheme.primary, isNot(AppTheme.brandSteel));
    });
  });

  group('AppTheme — Arabic typography', () {
    test('letterSpacing is zero across the text theme', () {
      // Material's Latin-tuned POSITIVE tracking visually breaks the joins of a connected
      // script. Any non-zero value here is a rendering defect for Arabic, not a style choice.
      final text = AppTheme.light().textTheme;
      for (final style in <TextStyle?>[
        text.displayLarge, text.displayMedium, text.displaySmall,
        text.headlineLarge, text.headlineMedium, text.headlineSmall,
        text.titleLarge, text.titleMedium, text.titleSmall,
        text.bodyLarge, text.bodyMedium, text.bodySmall,
        text.labelLarge, text.labelMedium, text.labelSmall,
      ]) {
        expect(style?.letterSpacing, 0, reason: 'every style must set letterSpacing: 0');
      }
    });

    test('body text is 16sp with 1.6 line height, and nothing drops below 14sp', () {
      final text = AppTheme.light().textTheme;
      expect(text.bodyLarge?.fontSize, 16);
      expect(text.bodyMedium?.fontSize, 16);
      expect(text.bodyLarge?.height, 1.6);

      for (final style in <TextStyle?>[
        text.bodySmall, text.labelSmall, text.labelMedium, text.labelLarge,
      ]) {
        expect(style!.fontSize!, greaterThanOrEqualTo(14));
      }
    });

    test('the bundled family is selected, with a device fallback', () {
      // D3.3: the subset covers Unicode RANGES, but a customer name from the Uqudo scan or the
      // Civil Registry can still fall outside them. The fallback is what makes that degrade to
      // the device font instead of to tofu.
      final theme = AppTheme.light();
      expect(theme.textTheme.bodyLarge?.fontFamily, 'IBMPlexSansArabic');
      expect(theme.textTheme.bodyLarge?.fontFamilyFallback, contains('sans-serif'));
    });
  });

  group('AppTheme — page transitions', () {
    test('Android uses a DIRECTION-NEUTRAL builder', () {
      // D6.1. Left unset, Android resolves to the predictive-back builder, which with no manifest
      // opt-in falls back to FadeForwards — a horizontal ±0.25 slide with LTR semantics hardcoded,
      // and NO built-in Material page transition mirrors for RTL. FadeUpwards' tween is
      // Offset(0.0, 0.25): x is zero, so it cannot slide the wrong way in an RTL app.
      final builders = AppTheme.light().pageTransitionsTheme.builders;
      expect(builders[TargetPlatform.android], isA<FadeUpwardsPageTransitionsBuilder>());
    });
  });

  group('AppTheme — surface treatment (S1: tinted canvas, white containers)', () {
    test('the canvas and the containers are DIFFERENT tones — the whole treatment', () {
      // This is the guard the design research explicitly asked for. The treatment IS the tonal
      // gap: if a later "simplification" collapses these two roles back together, every screen
      // silently returns to a flat page with nothing lifting off it, and no other test would
      // notice — before this pass, not one test in the suite asserted any surface colour.
      final theme = AppTheme.light();
      final scheme = theme.colorScheme;

      expect(theme.scaffoldBackgroundColor, scheme.surfaceContainerHigh);
      expect(theme.scaffoldBackgroundColor, isNot(scheme.surfaceContainerLowest));
      expect(theme.cardTheme.color, scheme.surfaceContainerLowest);
      expect(theme.listTileTheme.tileColor, scheme.surfaceContainerLowest);
      expect(theme.inputDecorationTheme.fillColor, scheme.surfaceContainerLowest);
      expect(theme.inputDecorationTheme.filled, isTrue);
    });

    test('a resting field is identifiable by its BORDER, because no fill difference can be', () {
      // The honest trade, encoded so it cannot be quietly undone. The canvas-vs-field fill
      // difference is ~1.22:1 and NO tone on M3's light ladder reaches WCAG 1.4.11's 3:1 — the
      // darkest, surfaceContainerHighest, is 1.29:1. So the fill can never be what identifies a
      // field, however far the tint is dialled, and the 1 dp outline hairline is load-bearing
      // rather than decorative. Anyone restoring the research's `borderSide: none` fails here.
      final theme = AppTheme.light();
      final scheme = theme.colorScheme;
      final resting = theme.inputDecorationTheme.enabledBorder! as OutlineInputBorder;

      expect(resting.borderSide.style, BorderStyle.solid);
      expect(resting.borderSide.width, greaterThanOrEqualTo(1));
      expect(
        _contrastRatio(resting.borderSide.color, theme.scaffoldBackgroundColor),
        greaterThanOrEqualTo(3.0),
        reason: 'the field boundary must be identifiable against the canvas it sits on',
      );
      expect(
        _contrastRatio(resting.borderSide.color, scheme.surfaceContainerLowest),
        greaterThanOrEqualTo(3.0),
        reason: 'and against the white fill of the field itself',
      );
    });

    test('body text still clears 4.5:1 on the tinted canvas', () {
      // The tint was deliberately pushed DOWN the ladder for visible separation, so the thing
      // worth proving is that pushing it did not cost text legibility on a mixed-literacy
      // audience's forty-field journey.
      final theme = AppTheme.light();
      expect(
        _contrastRatio(theme.colorScheme.onSurface, theme.scaffoldBackgroundColor),
        greaterThanOrEqualTo(4.5),
      );
      expect(
        _contrastRatio(theme.colorScheme.onSurfaceVariant, theme.scaffoldBackgroundColor),
        greaterThanOrEqualTo(4.5),
      );
    });

    test('the SegmentedButton unselected segment is the white container, not the canvas', () {
      // The signature screen's draw-vs-upload chooser was the one batch-one component reaching
      // this theme through nothing at all. M3's default leaves the UNSELECTED background null —
      // transparent — so the tinted canvas showed through a resting interactive control that
      // every other surface in this app renders as a white container.
      //
      // Asserted as ROLES rather than hexes, the same rule the rest of this theme follows.
      final theme = AppTheme.light();
      final scheme = theme.colorScheme;
      final background = theme.segmentedButtonTheme.style!.backgroundColor!;

      expect(background.resolve({}), scheme.surfaceContainerLowest);
      expect(background.resolve({WidgetState.selected}), scheme.secondaryContainer);
      // The two states must remain distinguishable from each other — a chooser whose selected
      // and unselected segments collapse to one colour stops being a chooser.
      expect(background.resolve({}), isNot(background.resolve({WidgetState.selected})));
    });

    test('an OPEN dropdown menu is the white container role, not the near-canvas default', () {
      // Material has NO theme slot for a dropdown menu's surface: `dropdown.dart` paints it with
      // `dropdownColor ?? Theme.of(context).canvasColor`, and `canvasColor` defaults to
      // `colorScheme.surface` — 1.17:1 against this app's canvas. So the closed state of a
      // dropdown was a white container (via `inputDecorationTheme`) while its open state was a
      // different, near-canvas tone: one control disagreeing with itself.
      //
      // This pins the value the three call sites pass. If a future change sets `canvasColor`
      // globally instead, this still passes only while the two agree.
      final scheme = AppTheme.light().colorScheme;
      expect(AppTheme.dropdownMenuSurface(scheme), scheme.surfaceContainerLowest);
      expect(AppTheme.dropdownMenuSurface(scheme), isNot(scheme.surface));
    });

    test('depth comes from tone, never from elevation', () {
      // Flat fills only — no shadow, no gradient. Both because the research's whole argument is
      // that containment does the work, and because shadows are the expensive half on the
      // API-28 pilot handset's legacy renderer.
      final theme = AppTheme.light();
      expect(theme.cardTheme.elevation, 0);
      expect(theme.appBarTheme.elevation, 0);
      expect(theme.appBarTheme.surfaceTintColor, Colors.transparent);
    });
  });

  test('the app is light-only for the pilot', () {
    expect(AppTheme.light().brightness, Brightness.light);
    expect(AppTheme.light().colorScheme.brightness, Brightness.light);
  });
}
