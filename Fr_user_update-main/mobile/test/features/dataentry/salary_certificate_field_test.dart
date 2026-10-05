import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/dataentry/salary_certificate_field.dart';

/// BL-105. The field's whole job after S8-14 is to say only what is true about the attachment.
///
/// Before that it had one state: a path existed, so it printed «تم إرفاق» — *attached* — which
/// proved only that a file was on the handset. The bank never received it and the file was deleted
/// with the rest of local state when the session cleared, so the confirmation named something that
/// had never happened. These tests pin the three states apart.
void main() {
  Future<void> pumpField(
    WidgetTester tester, {
    String? path,
    bool uploaded = false,
    bool uploading = false,
    VoidCallback? onRetryUpload,
  }) {
    return tester.pumpWidget(
      MaterialApp(
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: Scaffold(
            body: SalaryCertificateField(
              path: path,
              uploaded: uploaded,
              uploading: uploading,
              onRetryUpload: onRetryUpload,
              onChanged: (_) {},
            ),
          ),
        ),
      ),
    );
  }

  testWidgets('with no file picked it claims nothing at all', (tester) async {
    await pumpField(tester);

    expect(find.textContaining('تم إرفاق:'), findsNothing);
    expect(find.textContaining('لم يتم إرفاق'), findsNothing);
  });

  testWidgets('ACCEPTED by the backend is the only state that says «تم إرفاق»', (tester) async {
    await pumpField(tester, path: '/tmp/salary_certificate.pdf', uploaded: true);

    expect(find.textContaining('تم إرفاق:'), findsOneWidget);
    expect(find.textContaining('لم يتم إرفاق'), findsNothing);
  });

  testWidgets(
    'a picked-but-not-accepted file says so plainly and offers a retry — the BL-105 defect',
    (tester) async {
      // This is the exact state that used to render «تم إرفاق». A file is on the handset and the
      // bank does not have it.
      var retried = 0;
      await pumpField(
        tester,
        path: '/tmp/salary_certificate.jpg',
        uploaded: false,
        onRetryUpload: () => retried++,
      );

      // Matched on the colon form: «تم إرفاق» alone is a substring of «لم يتم إرفاق», so a
      // looser finder would pass against the very copy this test exists to distinguish.
      expect(find.textContaining('تم إرفاق:'), findsNothing);
      expect(find.textContaining('لم يتم إرفاق'), findsOneWidget);

      await tester.tap(find.widgetWithText(TextButton, 'إعادة محاولة الإرفاق'));
      expect(retried, 1);
    },
  );

  testWidgets('an upload in flight says it is in progress, not that it is done', (tester) async {
    await pumpField(tester, path: '/tmp/salary_certificate.jpg', uploading: true);

    expect(find.textContaining('جارٍ إرفاق'), findsOneWidget);
    expect(find.textContaining('تم إرفاق:'), findsNothing);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });

  testWidgets(
    'the failure state still lets the customer continue — the certificate gates nothing',
    (tester) async {
      // customer.md Stage 6: the attachment "gates nothing, must never block completion". A fix
      // that blocked Next on a failed upload would be a worse defect than the false confirmation
      // it replaced, so the copy explicitly tells the customer they may carry on.
      await pumpField(tester, path: '/tmp/salary_certificate.jpg', uploaded: false);

      expect(find.textContaining('يمكنك المتابعة'), findsOneWidget);
    },
  );

  testWidgets(
    'both pick controls are disabled while an upload is in flight — the BL-105 race guard',
    (tester) async {
      // Found by `@agent-reviewer` on the S8-14 diff. With both buttons live, pick A -> upload A
      // in flight -> pick B -> A succeeds would stamp the draft «تم إرفاق» while the screen names
      // B, which the bank does not have: the BL-105 falsehood rebuilt out of a race. The screen
      // also discards a stale result; this stops the race being easy to start at all.
      await pumpField(tester, path: '/tmp/salary_certificate.jpg', uploading: true);

      for (final label in ['التقاط صورة', 'اختيار ملف']) {
        final button = tester.widget<OutlinedButton>(
          find.widgetWithText(OutlinedButton, label),
        );
        expect(button.onPressed, isNull, reason: label);
      }
    },
  );

  testWidgets('the pick controls are live again once no upload is in flight', (tester) async {
    await pumpField(tester, path: '/tmp/salary_certificate.jpg', uploading: false);

    for (final label in ['التقاط صورة', 'اختيار ملف']) {
      final button = tester.widget<OutlinedButton>(
        find.widgetWithText(OutlinedButton, label),
      );
      expect(button.onPressed, isNotNull, reason: label);
    }
  });
}
