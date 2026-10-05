import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/dataentry/data_entry_providers.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/reference/reference_providers.dart';
import 'package:mobile/features/dataentry/reference_item_picker.dart';
import 'package:mobile/features/dataentry/stage6_screen.dart';

import '../../core/dataentry/fake_data_entry_api.dart';

final _countryItems = [
  ReferenceItem(
    listCode: 'country',
    version: 1,
    itemCode: 'SD',
    labelAr: 'السودان',
    labelEn: 'Sudan',
    searchAr: 'السودان',
    searchEn: 'sudan',
    sortOrdinal: 1,
    isActive: true,
  ),
];

final _adminDivisionItems = [
  ReferenceItem(
    listCode: 'admin_division',
    version: 1,
    itemCode: 'SD',
    labelAr: 'السودان',
    searchAr: 'السودان',
    sortOrdinal: 1,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'admin_division',
    version: 1,
    itemCode: '31',
    parentCode: 'SD',
    labelAr: 'الخرطوم',
    searchAr: 'الخرطوم',
    sortOrdinal: 2,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'admin_division',
    version: 1,
    itemCode: '3101',
    parentCode: '31',
    labelAr: 'الخرطوم بحري',
    searchAr: 'الخرطوم بحري',
    sortOrdinal: 3,
    isActive: true,
  ),
];

void main() {
  Future<SessionDatabase> seedProfile() async {
    final db = SessionDatabase.forTesting();
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '16',
            verifiedAccountNumber: '0000000001',
            resumeStage: 'stage6',
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );
    return db;
  }

  Future<ReferenceDatabase> seedAdminDivisionRoot() async {
    final db = ReferenceDatabase.forTesting();
    await db
        .into(db.referenceLists)
        .insert(
          ReferenceListsCompanion.insert(
            listCode: 'admin_division',
            version: 1,
            contentHash: 'irrelevant',
            itemCount: _adminDivisionItems.length,
            isHierarchical: true,
            rootItemCode: const Value('SD'),
            rootCountryVersion: const Value(1),
            nameAr: 'التقسيم الإداري',
            nameEn: 'Administrative division',
            publishedAt: DateTime.utc(2026, 1, 1),
            fetchedAt: DateTime.utc(2026, 1, 1),
            isActiveVersion: const Value(true),
          ),
        );
    return db;
  }

  /// [certificatePath] seeds `DataEntryDraft.salaryCertificatePath` before the screen builds,
  /// standing in for a customer who picked a certificate whose upload never succeeded — a path with
  /// no acceptance stamp is exactly that state (BL-122).
  Future<({FakeDataEntryApi api, String? Function() landedPath})> pumpStage6(
    WidgetTester tester, {
    String? certificatePath,
    bool certificateUploaded = false,
  }) async {
    final sessionDb = await seedProfile();
    addTearDown(sessionDb.close);
    if (certificatePath != null) {
      await sessionDb
          .into(sessionDb.dataEntryDraft)
          .insertOnConflictUpdate(
            DataEntryDraftCompanion.insert(
              id: const Value(0),
              salaryCertificatePath: Value(certificatePath),
              salaryCertificateUploadedAt: Value(
                certificateUploaded ? DateTime.now() : null,
              ),
              updatedAt: DateTime.now(),
            ),
          );
    }
    final referenceDb = await seedAdminDivisionRoot();
    addTearDown(referenceDb.close);
    final api = FakeDataEntryApi();
    String? landedPath;

    final router = GoRouter(
      initialLocation: '/stage-6',
      routes: [
        GoRoute(path: '/stage-6', builder: (context, state) => const Stage6Screen()),
        GoRoute(
          path: '/stage-5',
          builder: (context, state) {
            landedPath = '/stage-5';
            return const Scaffold(body: Text('stage 5'));
          },
        ),
        GoRoute(
          path: '/stage-7',
          builder: (context, state) {
            landedPath = '/stage-7';
            return const Scaffold(body: Text('stage 7'));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(sessionDb),
          referenceDatabaseProvider.overrideWithValue(referenceDb),
          dataEntryApiProvider.overrideWithValue(api),
          countryItemsProvider.overrideWith((ref) => Stream.value(_countryItems)),
          adminDivisionItemsProvider.overrideWith((ref) => Stream.value(_adminDivisionItems)),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (api: api, landedPath: () => landedPath);
  }

  testWidgets('Next is a no-op with nothing filled in', (tester) async {
    final (:api, :landedPath) = await pumpStage6(tester);

    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(api.submitStage6CallCount, 0);
  });

  // Stage 6 used to be the last screen this app built, so its Next advanced to 'beyondStage6' and
  // landed on the post-stage-6 placeholder. S5-07 put Stage 7 in between: Next now advances to
  // 'stage7' and lands there.
  testWidgets('a full valid submission advances to stage7 and lands on /stage-7', (
    tester,
  ) async {
    final (:api, :landedPath) = await pumpStage6(tester);

    await tester.enterText(find.widgetWithText(TextField, 'جهة العمل'), 'شركة الاتصالات');
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(PickerField, 'الولاية'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(PickerField, 'المحلية / المحافظة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم بحري'));
    await tester.pumpAndSettle();

    for (final label in ['المدينة', 'المنطقة', 'الشارع', 'المربع']) {
      final field = find.widgetWithText(TextField, label);
      await tester.ensureVisible(field);
      await tester.pumpAndSettle();
      await tester.enterText(field, 'قيمة');
      await tester.pumpAndSettle();
    }

    final nextButton = find.widgetWithText(FilledButton, 'التالي');
    await tester.ensureVisible(nextButton);
    await tester.pumpAndSettle();
    await tester.tap(nextButton);
    await tester.pumpAndSettle();

    expect(landedPath(), '/stage-7');
    expect(api.submitStage6CallCount, 1);
    expect(api.lastStage6Args!['employer'], 'شركة الاتصالات');
    expect(api.lastStage6Args!['stateCode'], '31');
    expect(api.lastStage6Args!['localityCode'], '3101');
  });

  testWidgets('Back returns to /stage-5', (tester) async {
    final (:api, :landedPath) = await pumpStage6(tester);

    await tester.tap(find.widgetWithText(OutlinedButton, 'السابق'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/stage-5');
  });

  testWidgets('offers camera-capture and file-picker actions for the optional salary certificate',
      (tester) async {
    await pumpStage6(tester);

    expect(find.text('التقاط صورة'), findsOneWidget);
    expect(find.text('اختيار ملف'), findsOneWidget);
  });

  // BL-122 — what Next tells the bank about the certificate. Both cases below leave the bank with
  // no certificate file at all; the claim is the only thing that tells them apart, and without it
  // an operator reviewing either profile sees the identical absence.
  Future<void> completeFormAndTapNext(WidgetTester tester) async {
    await tester.enterText(find.widgetWithText(TextField, 'جهة العمل'), 'شركة الاتصالات');
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(PickerField, 'الولاية'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(PickerField, 'المحلية / المحافظة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم بحري'));
    await tester.pumpAndSettle();

    for (final label in ['المدينة', 'المنطقة', 'الشارع', 'المربع']) {
      final field = find.widgetWithText(TextField, label);
      await tester.ensureVisible(field);
      await tester.pumpAndSettle();
      await tester.enterText(field, 'قيمة');
      await tester.pumpAndSettle();
    }

    final nextButton = find.widgetWithText(FilledButton, 'التالي');
    await tester.ensureVisible(nextButton);
    await tester.pumpAndSettle();
    await tester.tap(nextButton);
    await tester.pumpAndSettle();
  }

  testWidgets('Next reports no certificate attached when the customer picked none', (
    tester,
  ) async {
    final (:api, :landedPath) = await pumpStage6(tester);

    await completeFormAndTapNext(tester);

    expect(landedPath(), '/stage-7');
    expect(api.lastStage6Args!['salaryCertificateAttached'], isFalse);
  });

  testWidgets('Next reports a certificate attached when the customer picked one', (tester) async {
    // The draft is stamped as already accepted ONLY to keep this test off the Next-time upload
    // retry, which does real file I/O (`File.exists`, `readAsBytes`) that never completes inside
    // `pumpAndSettle`'s fake-async zone. The claim under test does not depend on the stamp — it is
    // `salaryCertificatePath != null` — and the case that matters most to BL-122, a path with NO
    // stamp (picked, never uploaded), is proven at the repository level instead, in
    // `data_entry_repository_test.dart`'s BL-122 group, which runs under plain `test()` and can
    // therefore drive the real upload failure end to end.
    final (:api, :landedPath) = await pumpStage6(
      tester,
      certificatePath: '/does/not/matter/salary_certificate.jpg',
      certificateUploaded: true,
    );

    await completeFormAndTapNext(tester);

    expect(landedPath(), '/stage-7');
    expect(api.lastStage6Args!['salaryCertificateAttached'], isTrue);
  });
}
