import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/forms/field_error_state.dart';

/// A minimal host with the same shape the real screens use: two text fields plus the form-level
/// summary line, all driven by the mixin.
class _Host extends StatefulWidget {
  const _Host();

  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> with FieldErrorState<_Host> {
  @override
  void dispose() {
    disposeFieldNodes();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.rtl,
      child: MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              TextField(
                focusNode: fieldNode('city'),
                decoration: fieldDecoration(label: 'المدينة', field: 'city'),
              ),
              TextField(
                focusNode: fieldNode('area'),
                decoration: fieldDecoration(label: 'المنطقة', field: 'area'),
              ),
              if (hasFormLevelError) Text(fieldErrorMessage!, key: const Key('summary')),
              TextButton(
                key: const Key('fieldFail'),
                onPressed: () => showFieldError(field: 'area', message: 'أدخل المنطقة.'),
                child: const Text('field fail'),
              ),
              TextButton(
                key: const Key('formFail'),
                onPressed: () => showFieldError(message: 'تعذر حفظ البيانات.'),
                child: const Text('form fail'),
              ),
              TextButton(
                key: const Key('clear'),
                onPressed: () => setState(clearFieldError),
                child: const Text('clear'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}


/// A host shaped like the REAL screens for the focus-chain tests: text fields with a
/// non-text focusable BETWEEN them, which is the whole reason walk comment 2 exists.
class _ChainHost extends StatefulWidget {
  const _ChainHost({required this.onDone});

  final VoidCallback onDone;

  @override
  State<_ChainHost> createState() => _ChainHostState();
}

class _ChainHostState extends State<_ChainHost> with FieldErrorState<_ChainHost> {
  @override
  List<String> get textFieldOrder => const ['first', 'second'];

  @override
  void dispose() {
    disposeFieldNodes();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.rtl,
      child: MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              TextField(
                key: const Key('first'),
                focusNode: fieldNode('first'),
                textInputAction: fieldAction('first'),
                onSubmitted: fieldSubmit('first'),
              ),
              // The trap: on the real screens this is a PickerField or a dropdown, and it is
              // what Flutter's default `nextFocus()` was landing on.
              ElevatedButton(
                key: const Key('picker'),
                onPressed: () {},
                child: const Text('picker'),
              ),
              TextField(
                key: const Key('second'),
                focusNode: fieldNode('second'),
                textInputAction: fieldAction('second'),
                onSubmitted: fieldSubmit('second', onDone: widget.onDone),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

void main() {
  group('FieldErrorState — D5.6', () {
    testWidgets('a message WITH a field renders on that field and not as the summary', (
      tester,
    ) async {
      await tester.pumpWidget(const _Host());
      await tester.tap(find.byKey(const Key('fieldFail')));
      await tester.pumpAndSettle();

      // Attached to the offending field...
      final area = tester.widget<TextField>(
        find.byWidgetPredicate(
          (w) => w is TextField && w.decoration?.labelText == 'المنطقة',
        ),
      );
      expect(area.decoration?.errorText, 'أدخل المنطقة.');

      // ...and NOT also repeated at the bottom. Showing the same sentence twice was the thing
      // this change exists to stop.
      expect(find.byKey(const Key('summary')), findsNothing);
    });

    testWidgets('only the offending field carries the message', (tester) async {
      await tester.pumpWidget(const _Host());
      await tester.tap(find.byKey(const Key('fieldFail')));
      await tester.pumpAndSettle();

      final city = tester.widget<TextField>(
        find.byWidgetPredicate(
          (w) => w is TextField && w.decoration?.labelText == 'المدينة',
        ),
      );
      expect(city.decoration?.errorText, isNull);
    });

    testWidgets('the offending field takes focus — which is what scrolls it into view', (
      tester,
    ) async {
      await tester.pumpWidget(const _Host());
      await tester.tap(find.byKey(const Key('fieldFail')));
      await tester.pumpAndSettle();

      final state = tester.state<_HostState>(find.byType(_Host));
      expect(state.fieldNode('area').hasFocus, isTrue);
      expect(state.fieldNode('city').hasFocus, isFalse);
    });

    testWidgets('a message with NO field falls back to the summary line', (tester) async {
      await tester.pumpWidget(const _Host());
      await tester.tap(find.byKey(const Key('formFail')));
      await tester.pumpAndSettle();

      // A save failure belongs to no field, so the bottom line is the only place it can go.
      expect(find.byKey(const Key('summary')), findsOneWidget);
      expect(find.text('تعذر حفظ البيانات.'), findsOneWidget);

      for (final label in ['المدينة', 'المنطقة']) {
        final field = tester.widget<TextField>(
          find.byWidgetPredicate((w) => w is TextField && w.decoration?.labelText == label),
        );
        expect(field.decoration?.errorText, isNull);
      }
    });

    testWidgets('clearing removes both the field message and the summary', (tester) async {
      await tester.pumpWidget(const _Host());
      await tester.tap(find.byKey(const Key('fieldFail')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('clear')));
      await tester.pumpAndSettle();

      final area = tester.widget<TextField>(
        find.byWidgetPredicate(
          (w) => w is TextField && w.decoration?.labelText == 'المنطقة',
        ),
      );
      expect(area.decoration?.errorText, isNull);
      expect(find.byKey(const Key('summary')), findsNothing);
    });

    testWidgets('a field node is stable across rebuilds', (tester) async {
      // If `fieldNode` handed back a new node each build, focus would be lost on every setState
      // and the scroll-into-view behaviour would silently stop working.
      await tester.pumpWidget(const _Host());
      final state = tester.state<_HostState>(find.byType(_Host));
      final first = state.fieldNode('city');
      await tester.tap(find.byKey(const Key('formFail')));
      await tester.pumpAndSettle();
      expect(identical(state.fieldNode('city'), first), isTrue);
    });
  });

  group('the keyboard next key — walk comment 2 (2026-09-10)', () {
    /// **The regression test for the reported defect.** Every field already declared
    /// `TextInputAction.next` and the key did nothing visible. It was not inert: Flutter's default
    /// action is `FocusScope.nextFocus()`, which moved focus to the ElevatedButton standing in for
    /// a PickerField here — so the keyboard closed and the screen looked dead.
    testWidgets('next moves to the next TEXT field, skipping the picker between them', (
      tester,
    ) async {
      await tester.pumpWidget(_ChainHost(onDone: () {}));

      final state = tester.state<_ChainHostState>(find.byType(_ChainHost));
      state.fieldNode('first').requestFocus();
      await tester.pump();
      expect(state.fieldNode('first').hasFocus, isTrue);

      await tester.testTextInput.receiveAction(TextInputAction.next);
      await tester.pump();

      expect(
        state.fieldNode('second').hasFocus,
        isTrue,
        reason: 'focus must land on the next TEXT field, not on the picker between them',
      );
      expect(state.fieldNode('first').hasFocus, isFalse);
    });

    /// The last field finishes the form, so a customer never has to reach for the button.
    testWidgets('done on the last field submits and closes the keyboard', (tester) async {
      var doneCalls = 0;
      await tester.pumpWidget(_ChainHost(onDone: () => doneCalls++));

      final state = tester.state<_ChainHostState>(find.byType(_ChainHost));
      state.fieldNode('second').requestFocus();
      await tester.pump();

      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();

      expect(doneCalls, 1);
      expect(state.fieldNode('second').hasFocus, isFalse, reason: 'the keyboard is dismissed');
    });

    /// The action and the behaviour are derived from ONE list, so they cannot disagree — a field
    /// showing «next» that submits is the same untrue affordance as the dead key this replaces.
    testWidgets('the action label matches what the field actually does', (tester) async {
      await tester.pumpWidget(_ChainHost(onDone: () {}));

      final first = tester.widget<TextField>(find.byKey(const Key('first')));
      final second = tester.widget<TextField>(find.byKey(const Key('second')));
      expect(first.textInputAction, TextInputAction.next);
      expect(second.textInputAction, TextInputAction.done, reason: 'last field finishes');
    });

    /// A screen that has not opted in keeps Flutter's stock behaviour untouched.
    testWidgets('a screen with no declared order is left alone', (tester) async {
      await tester.pumpWidget(const _Host());
      final state = tester.state<_HostState>(find.byType(_Host));
      expect(state.textFieldOrder, isEmpty);
      expect(state.fieldAction('city'), TextInputAction.next);
    });

    /// **A field outside a declared order must do NOTHING, not submit.** The first version fell
    /// through to the submit branch, so `fieldAction` labelled the key «next» while `fieldSubmit`
    /// finished the form. Unreachable from today's screens; this is what stops the next screen
    /// that declares a partial order from inheriting it. Raised by `@agent-reviewer`.
    testWidgets('a field outside the order neither advances nor submits', (tester) async {
      var doneCalls = 0;
      await tester.pumpWidget(_ChainHost(onDone: () => doneCalls++));
      final state = tester.state<_ChainHostState>(find.byType(_ChainHost));

      state.fieldNode('first').requestFocus();
      await tester.pump();

      // 'stray' is not in textFieldOrder.
      state.fieldSubmit('stray', onDone: () => doneCalls++)('');
      await tester.pump();

      expect(doneCalls, 0, reason: 'an unlisted field must not submit the form');
      expect(state.fieldNode('first').hasFocus, isTrue, reason: 'and must not move focus');
    });
  });
}
