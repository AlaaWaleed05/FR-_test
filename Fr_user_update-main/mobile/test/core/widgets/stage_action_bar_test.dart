import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/theme/app_theme.dart';
import 'package:mobile/core/widgets/stage_action_bar.dart';

/// The inset a 3-button Android navigation bar contributes. Walk comment 9 was reported on a
/// Samsung A55 with navigation set to BUTTONS; 48 dp is that bar's height.
const double _navigationBarInset = 48;

/// Pumps [bar] at the foot of a screen, under a [MediaQuery] carrying a bottom system inset.
///
/// **This is what makes the defect testable at all.** `SafeArea` reads `MediaQuery.padding`, and
/// the test binding supplies zero padding by default — which is exactly why every existing widget
/// test passed against a button the customer could not reach. Declaring the inset here reproduces
/// the handset.
Future<void> _pump(
  WidgetTester tester,
  Widget bar, {
  double bottomInset = _navigationBarInset,
  double keyboardInset = 0,
  bool settle = true,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      // **`copyWith`, never a bare `MediaQueryData`.** `launch_screen_test.dart` in this same
      // suite documents the trap: a bare one carries `size: Size.zero`, which collapses the
      // layout and fails the assertion for a reason unrelated to what is being tested. Building
      // it through `builder` is what gives us a real ambient MediaQuery to copy. Raised by
      // `@agent-reviewer` — the first version passed only because it measured against
      // `MaterialApp`, which sits ABOVE the override.
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(
          padding: EdgeInsets.only(bottom: bottomInset),
          viewInsets: EdgeInsets.only(bottom: keyboardInset),
        ),
        child: Directionality(textDirection: TextDirection.rtl, child: child!),
      ),
      home: Scaffold(
        body: Column(
          children: [const Expanded(child: SizedBox()), bar],
        ),
      ),
    ),
  );
  // `settle: false` for the busy case — a `CircularProgressIndicator` never stops animating, so
  // `pumpAndSettle` waits for a quiescence that cannot arrive and times out after 10 minutes.
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
  }
}

/// The distance from the bottom of the action BUTTON to the bottom of the screen.
///
/// **Measures the button, not its label.** The first version of these tests measured
/// `find.text('التالي')`, which sits ~12 dp above the button's own bottom edge inside a
/// `FilledButton`'s 48 dp tap target — so every assertion carried 12 dp of accidental slack and
/// the exact-value test failed with 28 where 16 was expected. The button's rect is what actually
/// has to clear the navigation bar, because the whole of it is the tap target.
double _clearanceBelowAction(WidgetTester tester) {
  final screenBottom = tester.getSize(find.byType(MaterialApp)).height;
  return screenBottom - tester.getRect(find.byType(FilledButton)).bottom;
}

void main() {
  group('StageActionBar — walk comment 9', () {
    /// **The regression test for the defect itself.** Before the fix the primary button's bottom
    /// edge sat flush with the screen's, i.e. underneath the system navigation bar, where a tap
    /// goes to the navigation bar and not to the app. The customer could not advance the journey.
    testWidgets('keeps the primary action clear of the navigation bar', (tester) async {
      await _pump(
        tester,
        StageActionBar.previousNext(onNext: () {}, onPrevious: () {}),
      );

      expect(
        _clearanceBelowAction(tester),
        greaterThanOrEqualTo(_navigationBarInset),
        reason:
            'the button must sit entirely above the system navigation bar; '
            'flush with the screen bottom is the defect',
      );
    });

    /// The same assertion for the free-form child, which is the shape Stage 2 and the
    /// session-pending screen use.
    testWidgets('holds an arbitrary child clear too', (tester) async {
      await _pump(
        tester,
        StageActionBar(
          child: FilledButton(onPressed: () {}, child: const Text('التراجع')),
        ),
      );

      expect(
        _clearanceBelowAction(tester),
        greaterThanOrEqualTo(_navigationBarInset),
      );
    });

    /// On a gesture handset there is no bar to avoid, and the inset is zero. The fix must not
    /// spend real estate there — a fixed bottom margin would have "fixed" the defect while
    /// costing every gesture user 48 dp forever.
    testWidgets('costs nothing when there is no system bar', (tester) async {
      await _pump(
        tester,
        StageActionBar.previousNext(onNext: () {}),
        bottomInset: 0,
      );

      // Only the bar's own 16 dp padding remains — no fixed margin was spent on a bar that
      // is not there.
      expect(_clearanceBelowAction(tester), closeTo(16, 0.5));
    });

    testWidgets('renders «السابق» only when a back action is given', (tester) async {
      await _pump(tester, StageActionBar.previousNext(onNext: () {}));
      expect(find.text('السابق'), findsNothing);
      expect(find.text('التالي'), findsOneWidget);

      await _pump(
        tester,
        StageActionBar.previousNext(onNext: () {}, onPrevious: () {}),
      );
      expect(find.text('السابق'), findsOneWidget);
    });

    testWidgets('a null onNext leaves the primary action disabled', (tester) async {
      await _pump(tester, const StageActionBar.previousNext(onNext: null));

      final button = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(button.onPressed, isNull);
    });

    /// **Pins the keyboard claim `StageActionBar`'s doc comment makes**, which was prose-only
    /// until `@agent-reviewer` pointed out nothing enforced it.
    ///
    /// The real-device shape: `adjustResize` is set in AndroidManifest.xml, and Flutter computes
    /// `padding = max(0, viewPadding - viewInsets)` — so while the keyboard is up the navigation
    /// inset is already absorbed and `padding.bottom` reads 0. `Scaffold` then shrinks the body
    /// by `viewInsets.bottom`, which is what lifts the bar clear of the keyboard. All the bar
    /// itself must contribute is its own 16 dp.
    ///
    /// So measured from the screen's bottom edge the button sits at `keyboard + 16`, and the
    /// assertion is that it sits at EXACTLY that — adding `viewInsets` inside the widget, the
    /// "obvious fix" the doc comment warns against, would put it at `keyboard * 2 + 16` and open
    /// a second keyboard-sized gap.
    testWidgets('rides above the keyboard without double-counting it', (tester) async {
      const keyboard = 300.0;
      await _pump(
        tester,
        StageActionBar.previousNext(onNext: () {}),
        bottomInset: 0,
        keyboardInset: keyboard,
      );

      expect(
        _clearanceBelowAction(tester),
        closeTo(keyboard + 16, 0.5),
        reason:
            'Scaffold.resizeToAvoidBottomInset already lifted the body by $keyboard; '
            'the bar adds only its own 16 dp on top of that',
      );
      expect(
        _clearanceBelowAction(tester),
        lessThan(keyboard * 2),
        reason: 'a second keyboard-sized gap means viewInsets was counted twice',
      );
    });

    /// `busy` swaps the label for a spinner AND blocks the press, so a double-tap cannot submit
    /// twice while the first call is in flight.
    testWidgets('busy shows a spinner and refuses a second press', (tester) async {
      var presses = 0;
      await _pump(
        tester,
        StageActionBar.previousNext(onNext: () => presses++, busy: true),
        settle: false,
      );

      expect(find.text('التالي'), findsNothing);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      await tester.tap(find.byType(FilledButton));
      await tester.pump();
      expect(presses, 0);
    });
  });
}
