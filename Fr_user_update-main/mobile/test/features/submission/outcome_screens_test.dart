import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/submission/outcome_screens.dart';

Future<void> _pump(WidgetTester tester, Widget screen) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Directionality(textDirection: TextDirection.rtl, child: screen),
    ),
  );
  await tester.pumpAndSettle();
}

/// The confirmation screen's two sentences. A customer whose profile is already APPROVED or
/// REJECTED must never see either of them — that pair is the whole of BL-106.
const _stillPending = 'تم إرسال طلبك إلى البنك للمراجعة والاعتماد';
const _notYetApproved = 'لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة.';

void main() {
  group('ApprovedScreen', () {
    testWidgets('says the update was approved and shows the reference number', (tester) async {
      await _pump(tester, const ApprovedScreen(referenceNumber: 'SFB-000000009'));

      expect(find.text('تم اعتماد تحديث بياناتك'), findsOneWidget);
      expect(find.text('SFB-000000009'), findsOneWidget);
      expect(find.text('الرقم المرجعي'), findsOneWidget);
    });

    testWidgets('does not tell an approved customer their request is still pending', (
      tester,
    ) async {
      await _pump(tester, const ApprovedScreen(referenceNumber: 'SFB-000000009'));

      expect(find.text(_stillPending), findsNothing);
      expect(find.text(_notYetApproved), findsNothing);
    });
  });

  group('RejectedScreen', () {
    testWidgets('says the update was NOT approved and shows the reference number', (tester) async {
      await _pump(tester, const RejectedScreen(referenceNumber: 'SFB-000000009'));

      expect(find.text('لم يتم اعتماد تحديث بياناتك'), findsOneWidget);
      expect(find.text('SFB-000000009'), findsOneWidget);
    });

    /// The defect BL-106 was filed for, stated directly: the app told a REJECTED customer their
    /// update was awaiting review, while the back office rendered «مرفوض» for the same profile.
    testWidgets('does not tell a rejected customer their request is still under review', (
      tester,
    ) async {
      await _pump(tester, const RejectedScreen(referenceNumber: 'SFB-000000009'));

      expect(find.text(_stillPending), findsNothing);
      expect(find.text(_notYetApproved), findsNothing);
      // "you will be notified of the result" — false: the decision has already been made.
      expect(find.textContaining('سيتم إشعارك'), findsNothing);
    });

    /// **The BL-005 fence.** The seven rejection reasons and their customer-facing Arabic copy are
    /// unapproved and sit with the product owner. This asserts the screen ships with NO reason
    /// rather than an invented one or a placeholder that reads as real — a placeholder is the
    /// failure mode here, because it looks finished and nobody comes back to it.
    testWidgets('names no rejection reason — BL-005 copy is unapproved', (tester) async {
      await _pump(tester, const RejectedScreen(referenceNumber: 'SFB-000000009'));

      for (final placeholder in [
        'REJ-01', 'REJ-02', 'REJ-03', 'REJ-04', 'REJ-05', 'REJ-06', 'REJ-07',
        'السبب:', // "Reason:" — a labelled reason row of any kind
        'سبب الرفض', // "rejection reason"
      ]) {
        expect(
          find.textContaining(placeholder),
          findsNothing,
          reason:
              'A rejection reason must not appear until BL-005\'s Arabic copy is approved by the '
              'bank. Found: $placeholder',
        );
      }
    });

    /// Some rejection reasons are not correctable by the customer at all, so an in-app retry
    /// would be a promise the bank has not made.
    testWidgets('offers no retry and no in-app resubmission', (tester) async {
      await _pump(tester, const RejectedScreen(referenceNumber: 'SFB-000000009'));

      expect(find.byType(FilledButton), findsNothing);
      expect(find.text('إعادة المحاولة'), findsNothing);
    });
  });
}
