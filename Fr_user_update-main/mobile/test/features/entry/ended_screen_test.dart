import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/features/entry/ended_screen.dart';

/// The sentence BL-123 exists to remove. Held here as the ONE place it still appears in a string
/// literal anywhere in this repository's Dart, so the guard below has something to search for.
const _theFalseSentence = 'تم استكمال تحديث بيانات هذا الحساب من قبل.';

Future<void> _pumpEnded(WidgetTester tester, {required void Function() onStart}) async {
  final router = GoRouter(
    initialLocation: '/ended',
    routes: [
      GoRoute(path: '/ended', builder: (context, state) => const EndedScreen()),
      GoRoute(
        path: '/account-entry',
        builder: (context, state) {
          onStart();
          return const Scaffold(body: Text('account entry'));
        },
      ),
    ],
  );
  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp.router(
        routerConfig: router,
        builder: (context, child) =>
            Directionality(textDirection: TextDirection.rtl, child: child!),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('EndedScreen says the journey ended without claiming an outcome', () {
    testWidgets('states the update ended and directs the customer to a branch', (tester) async {
      await _pumpEnded(tester, onStart: () {});

      expect(find.text('انتهى تحديث بيانات هذا الحساب'), findsOneWidget);
      expect(find.textContaining('يرجى زيارة أقرب فرع'), findsOneWidget);
    });

    /// The heart of BL-119's ruling. FOUR statuses reach this screen — submitted, approved,
    /// rejected, terminated_registry_mismatch — and the wire carries no discriminator, so any
    /// sentence claiming one of them is false for the other three. These are direct
    /// wrong-value assertions: they cannot pass against a screen that names an outcome, so no
    /// revert-restore proof is needed to show they bite.
    testWidgets('claims no outcome — not completed, approved, rejected or under review', (
      tester,
    ) async {
      await _pumpEnded(tester, onStart: () {});

      // The exact false sentence this screen replaced.
      expect(find.text(_theFalseSentence), findsNothing);
      // "completed" / "approved" / "rejected" / "under review" — each would be a lie at three
      // of the four statuses that arrive here.
      expect(find.textContaining('تم استكمال'), findsNothing);
      expect(find.textContaining('تم اعتماد'), findsNothing);
      expect(find.textContaining('مرفوض'), findsNothing);
      expect(find.textContaining('قيد المراجعة'), findsNothing);
    });

    /// S8-14's BlockedView rule, applied here: describe what was observed, never promise a
    /// remedy. A customer whose profile was REJECTED has no in-app recovery at all, so an
    /// "try again" control would be false for them specifically.
    testWidgets('offers no retry and promises no remedy', (tester) async {
      await _pumpEnded(tester, onStart: () {});

      expect(find.text('إعادة المحاولة'), findsNothing);
      expect(find.byType(FilledButton), findsNothing);
    });

    testWidgets('"back to start" returns to a fresh Stage 1a', (tester) async {
      var landedOnAccountEntry = false;
      await _pumpEnded(tester, onStart: () => landedOnAccountEntry = true);

      await tester.tap(find.text('العودة إلى البداية'));
      await tester.pumpAndSettle();

      expect(landedOnAccountEntry, isTrue);
    });
  });


  /// **The permanent guard, and the reason this slice is not just twelve edits.**
  ///
  /// BL-123 is the FIFTH recurrence of its own shape (BL-101, BL-065, BL-021, BL-105 are the
  /// others). Every previous fix found the sites, changed them, and left nothing behind that
  /// would notice a new one — so the next session re-derived the same wrong assumption from a
  /// comment or a backlog row and put the literal back. A screen that owns its copy removes the
  /// current twelve; this test is what notices a thirteenth.
  ///
  /// **What it catches** (widened after `@agent-reviewer` showed the first version was weaker
  /// than its own comment claimed). Each file is stripped of comments and normalised to ONE
  /// string, with whitespace collapsed and Dart's adjacent-string-literal concatenation folded
  /// away — so a spacing change, a dropped full stop, and a sentence split across two adjacent
  /// literals are all caught. That last one matters: splitting a long Arabic string over two
  /// lines is this repo's own idiom (`stage9_screen.dart:210-212` does it), so the line-by-line
  /// scan had a hole exactly the shape the next recurrence would take.
  ///
  /// It still does not police OTHER false sentences reaching `/terminal`. It is a tripwire on the
  /// specific regression that happened four times, not a proof of honesty in general.
  group('BL-123 guard — the false sentence never returns to lib/', () {
    /// Collapses whitespace and drops sentence punctuation, so «تم  استكمال … من قبل» and
    /// «تم استكمال … من قبل.» both reduce to the same needle.
    String normalise(String value) => value
        .replaceAll(RegExp(r'\s+'), ' ')
        .replaceAll(RegExp(r'[.،؛!؟]'), '')
        .trim();

    /// Strips `//` and `/* */` comments, then folds Dart's adjacent-string-literal concatenation
    /// so a sentence broken over two lines reads as one.
    ///
    /// Deliberately crude — a tripwire, not a parser — and NOT string-aware, which
    /// `@agent-reviewer` correctly noted has two silent-miss modes rather than the
    /// false-positives-only guarantee this comment first claimed:
    ///
    /// 1. a `//` earlier on the same line (a URL, a `'//'` path) would truncate the rest of that
    ///    line, taking an offending literal with it. Mitigated below: lines containing a quote
    ///    keep their `//` rather than being trimmed, trading a possible false positive for the
    ///    miss, which is the right way round for a guard whose job is to fail loudly.
    /// 2. a `/*` inside a string literal plus any later `*/` would erase everything between.
    ///    NOT mitigated — no `/*` sequence exists anywhere under `lib/` today, and handling it
    ///    properly needs a real lexer. If one ever appears, this is where to look.
    ///
    /// `+` concatenation is also unfolded, but `prefer_adjacent_string_concatenation` (via
    /// `flutter_lints`) makes it a lint error, so it is not a live hole.
    String codeOf(String source) {
      final withoutBlock = source.replaceAll(
        RegExp(r'/\*.*?\*/', dotAll: true),
        ' ',
      );
      final withoutLine = withoutBlock
          .split('\n')
          .map((line) {
            // A whole-line comment goes entirely, quotes and all — this is what lets
            // `EndedScreen`'s own doc comment quote the sentence to say what it replaced.
            final trimmed = line.trimLeft();
            if (trimmed.startsWith('//') || trimmed.startsWith('*')) return '';
            // Otherwise see mode (1) above: never trim a TRAILING `//` off a line that holds a
            // quote, or a `'//'` inside a string would swallow real code to the right of it.
            if (line.contains("'") || line.contains('"')) return line;
            final i = line.indexOf('//');
            return i == -1 ? line : line.substring(0, i);
          })
          .join('\n');
      // `'…'  '…'` and `"…"  "…"` joined across any whitespace, newlines included.
      return withoutLine
          .replaceAll(RegExp("'\\s*'"), '')
          .replaceAll(RegExp('"\\s*"'), '');
    }

    test('the already-completed claim appears in no Dart code under lib/', () {
      final needle = normalise(_theFalseSentence);
      final offenders = <String>[];

      for (final entity in Directory('lib').listSync(recursive: true)) {
        if (entity is! File || !entity.path.endsWith('.dart')) continue;
        if (normalise(codeOf(entity.readAsStringSync())).contains(needle)) {
          offenders.add(entity.path);
        }
      }

      expect(
        offenders,
        isEmpty,
        reason:
            'This sentence is FALSE for three of the four terminal statuses that reach it '
            '(submitted, approved, rejected, terminated_registry_mismatch — V0005), and the wire '
            'carries no discriminator to tell them apart (BL-119, closed as unnecessary '
            '2026-09-12). Route to `/ended` instead: EndedScreen owns the one sentence that is '
            'true for all four. Found in: ${offenders.join(', ')}',
      );
    });

    /// Regression tests for the guard itself — each is a case `@agent-reviewer` showed the
    /// line-by-line version would have missed or wrongly flagged.
    test('catches spacing and punctuation variants', () {
      final needle = normalise(_theFalseSentence);

      expect(
        normalise('تم  استكمال تحديث بيانات هذا الحساب من قبل').contains(needle),
        isTrue,
      );
      expect(normalise('انتهى تحديث بيانات هذا الحساب').contains(needle), isFalse);
    });

    test('catches the sentence split across adjacent string literals', () {
      final needle = normalise(_theFalseSentence);
      // Exactly the idiom `stage9_screen.dart:210-212` uses for its own long Arabic string.
      const split =
          "context.go('/terminal', extra: 'تم استكمال تحديث بيانات '\n"
          "    'هذا الحساب من قبل.');";

      expect(
        normalise(split).contains(needle),
        isFalse,
        reason: 'unfolded, the split literal does not match — this is the hole that existed',
      );
      expect(
        normalise(codeOf(split)).contains(needle),
        isTrue,
        reason: 'folded, it does — which is the fix',
      );
    });

    test('does not flag a comment that quotes the sentence', () {
      final needle = normalise(_theFalseSentence);

      for (final comment in [
        '/// «$_theFalseSentence» was removed',
        '// $_theFalseSentence',
        '/* $_theFalseSentence */',
      ]) {
        expect(
          normalise(codeOf(comment)).contains(needle),
          isFalse,
          reason: 'a false positive is as bad as a miss for a guard nobody trusts: $comment',
        );
      }
    });
  });
}
