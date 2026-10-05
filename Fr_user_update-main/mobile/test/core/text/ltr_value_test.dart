import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/text/ltr_value.dart';

/// Wraps in the same app-wide RTL the real app forces at `app.dart:26-27`, so these exercise the
/// actual condition the widget exists for rather than a neutral one.
Widget _rtl(Widget child) => Directionality(
  textDirection: TextDirection.rtl,
  child: MaterialApp(home: Scaffold(body: child)),
);

void main() {
  group('LtrValue — standalone values get their own bidi paragraph', () {
    testWidgets('renders LTR even though the surrounding app is RTL', (tester) async {
      await tester.pumpWidget(_rtl(const LtrValue('FRU-000000001')));

      final text = tester.widget<Text>(find.text('FRU-000000001'));
      expect(text.textDirection, TextDirection.ltr);
    });

    testWidgets('the VALUE ITSELF is never altered — no format characters inserted', (
      tester,
    ) async {
      await tester.pumpWidget(_rtl(const LtrValue('FRU-000000001')));

      final text = tester.widget<Text>(find.text('FRU-000000001'));
      expect(text.data, 'FRU-000000001');
      expect(text.data, isNot(contains('\u2068')));
      expect(text.data, isNot(contains('\u2069')));
    });

    testWidgets('passes through alignment and style', (tester) async {
      await tester.pumpWidget(
        _rtl(const LtrValue('123456', textAlign: TextAlign.center)),
      );

      final text = tester.widget<Text>(find.text('123456'));
      expect(text.textAlign, TextAlign.center);
    });
  });

  group('LtrValue.selectable — the reference number the customer copies', () {
    testWidgets('is selectable, LTR, and CLEAN', (tester) async {
      await tester.pumpWidget(_rtl(const LtrValue.selectable('FRU-000000001')));

      final selectable = tester.widget<SelectableText>(find.byType(SelectableText));
      expect(selectable.textDirection, TextDirection.ltr);

      // The point of the whole standalone/interpolated split. This value exists to be copied and
      // quoted at a branch; if isolation were done with FSI/PDI here, those two invisible
      // characters would travel into whatever the customer pastes and a support agent would
      // search for a reference number that does not match.
      expect(selectable.data, 'FRU-000000001');
      expect(selectable.data, isNot(contains('\u2068')));
      expect(selectable.data, isNot(contains('\u2069')));
    });
  });

  group('LtrValue.isolate — for a value interpolated INTO an Arabic run', () {
    test('wraps the value in FSI and PDI', () {
      expect(LtrValue.isolate('payslip-2026.pdf'), '\u2068payslip-2026.pdf\u2069');
    });

    test('pops the isolate explicitly so following text is unaffected', () {
      // Closing with PDI rather than letting the isolate run to the end of the paragraph is what
      // keeps any Arabic AFTER the value in its own direction.
      final sentence = 'تم إرفاق: ${LtrValue.isolate('a.pdf')} بنجاح';
      expect(sentence.endsWith('بنجاح'), isTrue);
      expect(sentence.indexOf('\u2069'), greaterThan(sentence.indexOf('\u2068')));
    });

    test('is a pure string transform — it adds exactly two characters', () {
      const value = 'ABC-123';
      expect(LtrValue.isolate(value).length, value.length + 2);
    });
  });
}
