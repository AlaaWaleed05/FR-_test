import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/theme/app_theme.dart';
import 'package:mobile/core/widgets/journey_progress.dart';

Future<void> _pump(WidgetTester tester, JourneyStep step) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      home: Directionality(
        textDirection: TextDirection.rtl,
        child: Scaffold(body: JourneyProgress(step: step)),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// The segment colours in TREE order, which is the order they are declared — deliberately not
/// "layout order", which is what the first version of this comment claimed. `widgetList` walks the
/// tree, and tree order is identical under LTR and RTL, so none of the colour assertions below say
/// anything about which SIDE the strip fills from. The RTL test does that, separately and on
/// purpose.
List<Color?> _segments(WidgetTester tester) => tester
    .widgetList<Container>(find.byType(Container))
    .map((c) => c.color)
    .toList();

/// Pumps with an explicit direction, for the RTL/LTR pair.
Future<void> _pumpIn(WidgetTester tester, JourneyStep step, TextDirection direction) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      home: Directionality(
        textDirection: direction,
        child: Scaffold(body: JourneyProgress(step: step)),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('JourneyProgress — walk comments 1 and 5 (2026-09-10)', () {
    /// Thirteen, by product-owner ruling. The design's caption says twelve while listing thirteen
    /// screens; the count was put to the product owner rather than guessed, so this asserts the
    /// ruling and will fail loudly if someone later "corrects" it back to the caption.
    test('there are thirteen steps, one per screen the customer sees', () {
      expect(JourneyStep.total, 13);
      expect(JourneyStep.values.first, JourneyStep.accountEntry);
      expect(JourneyStep.values.last, JourneyStep.confirmation);
    });

    test('positions are 1-based and contiguous', () {
      for (var i = 0; i < JourneyStep.values.length; i++) {
        expect(JourneyStep.values[i].position, i + 1);
      }
    });

    /// The handoff's own colour rule: done = navy, current = steel, remaining = steel-100.
    testWidgets('paints done, current and remaining in the handoff\'s three colours', (
      tester,
    ) async {
      await _pump(tester, JourneyStep.homeAddress); // step 6 of 13

      final segments = _segments(tester);
      expect(segments.length, JourneyStep.total);
      expect(segments.sublist(0, 5), everyElement(AppTheme.brandNavy), reason: 'done');
      expect(segments[5], AppTheme.brandSteel, reason: 'current');
      expect(
        segments.sublist(6),
        everyElement(isNot(anyOf(AppTheme.brandNavy, AppTheme.brandSteel))),
        reason: 'remaining',
      );
    });

    testWidgets('the first step has nothing behind it and the last nothing ahead', (tester) async {
      await _pump(tester, JourneyStep.accountEntry);
      expect(_segments(tester).first, AppTheme.brandSteel);
      expect(_segments(tester).where((c) => c == AppTheme.brandNavy), isEmpty);

      await _pump(tester, JourneyStep.confirmation);
      expect(_segments(tester).last, AppTheme.brandSteel);
      expect(
        _segments(tester).where((c) => c == AppTheme.brandNavy).length,
        JourneyStep.total - 1,
      );
    });

    testWidgets('names the step and counts it in Latin digits', (tester) async {
      await _pump(tester, JourneyStep.documentScan); // step 9

      expect(find.text('مسح الوثيقة'), findsOneWidget);
      expect(find.textContaining('9'), findsOneWidget);
      expect(find.textContaining('13'), findsOneWidget);
      // Never Arabic-Indic: everything DISPLAYED in this app is Latin digits.
      expect(find.textContaining('٩'), findsNothing);
    });

    /// The counter interpolates two numbers into an Arabic sentence in one `Text`, so it needs
    /// FSI/PDI — and a screen reader must not hear those control characters.
    testWidgets('isolates the digits but does not read the control characters aloud', (
      tester,
    ) async {
      await _pump(tester, JourneyStep.liveness); // step 11

      final counter = tester.widget<Text>(find.textContaining('الخطوة'));
      expect(counter.data, contains('\u2068'), reason: 'FSI');
      expect(counter.data, contains('\u2069'), reason: 'PDI');
      expect(counter.semanticsLabel, isNotNull);
      expect(counter.semanticsLabel, isNot(contains('\u2068')));
      expect(counter.semanticsLabel, contains('11'));
      expect(counter.semanticsLabel, contains('التحقق الشخصي'));
    });

    /// The bars repeat what the counter says, so a screen reader must not walk thirteen boxes.
    ///
    /// **This used to also assert "no semantics container in this widget", justified as «a
    /// container here broke Stage 4». That rule was false** — `@agent-reviewer` showed the
    /// container was never the cause (Stage 4's role check simply never ran until something
    /// dirtied its `RadioGroup` node; see the widget doc), and enforcing it would have
    /// permanently blocked a legitimate accessibility option on the strength of a wrong
    /// diagnosis. Removed rather than re-worded.
    testWidgets('excludes the decorative bars from semantics', (tester) async {
      await _pump(tester, JourneyStep.signature);

      expect(
        find.descendant(
          of: find.byType(ExcludeSemantics),
          matching: find.byType(Container),
        ),
        findsNWidgets(JourneyStep.total),
        reason: 'every segment bar is excluded from semantics',
      );
    });

    /// **Progress must fill the way Arabic reads.** Walk comment 10 of this same walk was a
    /// direction defect — the splash mark travelled against its own progress bar — so shipping a
    /// strip that fills left-to-right would be that defect again in a different place.
    ///
    /// Asserted by POSITION, not by colour. Every colour assertion in this file walks the widget
    /// tree, and tree order is the same under either direction, so they would all pass unchanged
    /// against a strip filling the wrong way. Raised by `@agent-reviewer`.
    testWidgets('fills right-to-left under RTL, and mirrors under LTR', (tester) async {
      await _pumpIn(tester, JourneyStep.homeAddress, TextDirection.rtl);
      final rtlBars = find.descendant(
        of: find.byType(ExcludeSemantics),
        matching: find.byType(Container),
      );
      expect(
        tester.getCenter(rtlBars.first).dx,
        greaterThan(tester.getCenter(rtlBars.last).dx),
        reason: 'step 1 sits on the RIGHT under RTL; progress runs right-to-left',
      );

      await _pumpIn(tester, JourneyStep.homeAddress, TextDirection.ltr);
      final ltrBars = find.descendant(
        of: find.byType(ExcludeSemantics),
        matching: find.byType(Container),
      );
      expect(
        tester.getCenter(ltrBars.first).dx,
        lessThan(tester.getCenter(ltrBars.last).dx),
        reason: 'no side is hardcoded — it derives from Directionality',
      );
    });

    testWidgets('every step renders without overflow on a narrow frame', (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      for (final step in JourneyStep.values) {
        await _pump(tester, step);
        expect(tester.takeException(), isNull, reason: step.name);
      }
    });

    /// **Binds each step to the screen that declares it.** `@agent-reviewer`: the 13 placements
    /// were asserted by nothing, so giving Stage 6 `JourneyStep.homeAddress` would pass analyze,
    /// every test and the coverage gate in silence — and that is the most copy-paste-prone part
    /// of the change.
    ///
    /// Reading the source is deliberate rather than pumping 13 screens: each of those needs its
    /// own database, router and provider overrides, and this catches the actual failure mode (a
    /// step used on the wrong screen, or used twice) at a fraction of the cost.
    test('every screen declares its own step, exactly once and in route order', () {
      const expected = <String, JourneyStep>{
        'features/entry/account_entry_screen.dart': JourneyStep.accountEntry,
        'features/entry/contact_channels_screen.dart': JourneyStep.contactChannels,
        'features/entry/channel_verification_screen.dart': JourneyStep.channelVerification,
        'features/dataentry/stage3_screen.dart': JourneyStep.personalData,
        'features/dataentry/stage4_screen.dart': JourneyStep.occupation,
        'features/dataentry/stage5_screen.dart': JourneyStep.homeAddress,
        'features/dataentry/stage6_screen.dart': JourneyStep.workAddress,
        'features/dataentry/stage7_screen.dart': JourneyStep.documentType,
        'features/identityscan/stage8_screen.dart': JourneyStep.documentScan,
        'features/identityscan/stage9_screen.dart': JourneyStep.registryReview,
        'features/liveness/stage10_screen.dart': JourneyStep.liveness,
        'features/signature/stage11_screen.dart': JourneyStep.signature,
        'features/submission/stage12_screen.dart': JourneyStep.confirmation,
      };

      // Every step is spoken for, and no step is used twice.
      expect(expected.length, JourneyStep.total);
      expect(expected.values.toSet().length, JourneyStep.total);
      expect(expected.values.toSet(), JourneyStep.values.toSet());

      final pattern = RegExp(r'JourneyProgress\(step: JourneyStep\.(\w+)\)');
      expected.forEach((relative, step) {
        final source = File('lib/$relative').readAsStringSync();
        final matches = pattern.allMatches(source).toList();
        expect(matches, hasLength(1), reason: '$relative must render the strip exactly once');
        expect(matches.single.group(1), step.name, reason: relative);
      });
    });

    /// The screens that deliberately carry NO strip. `/final-stages` is the handoff's own stated
    /// exclusion — the customer never sees it — and the terminal screens are outcomes, not steps.
    test('outcome and gate screens carry no strip', () {
      const none = [
        'features/submission/final_stages_gate_screen.dart',
        'features/submission/outcome_screens.dart',
        'features/submission/session_complete_screen.dart',
        'features/submission/confirmation_screen.dart',
        'features/entry/blocked_screen.dart',
        'features/entry/ended_screen.dart',
        'features/entry/terminal_screen.dart',
        'features/entry/session_pending_screen.dart',
        'features/entry/launch_screen.dart',
      ];
      for (final relative in none) {
        expect(
          File('lib/$relative').readAsStringSync(),
          isNot(contains('JourneyProgress')),
          reason: '$relative is not a step of the journey',
        );
      }
    });
  });
}
