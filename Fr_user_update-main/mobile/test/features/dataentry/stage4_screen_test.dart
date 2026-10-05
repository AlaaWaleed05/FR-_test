import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/dataentry/data_entry_models.dart';
import 'package:mobile/core/dataentry/data_entry_providers.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/reference/reference_providers.dart';
import 'package:mobile/features/dataentry/reference_item_picker.dart';
import 'package:mobile/features/dataentry/stage4_screen.dart';

import '../../core/dataentry/fake_data_entry_api.dart';

final _occupationItems = [
  ReferenceItem(
    listCode: 'occupation',
    version: 1,
    itemCode: '2',
    labelAr: 'مهندس',
    labelEn: 'Engineer',
    searchAr: 'مهندس',
    searchEn: 'engineer',
    sortOrdinal: 1,
    isActive: true,
  ),
];

final _incomeSourceItems = [
  ReferenceItem(
    listCode: 'income_source',
    version: 1,
    itemCode: 'RATIB',
    labelAr: 'راتب / أجر',
    labelEn: 'Salary',
    searchAr: 'راتب / اجر',
    searchEn: 'salary',
    sortOrdinal: 1,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'income_source',
    version: 1,
    itemCode: 'PENSION',
    labelAr: 'معاش تقاعدي',
    labelEn: 'Pension',
    searchAr: 'معاش تقاعدي',
    searchEn: 'pension',
    sortOrdinal: 2,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'income_source',
    version: 1,
    itemCode: 'OTHER',
    labelAr: 'أخرى',
    labelEn: 'Other',
    searchAr: 'اخرى',
    searchEn: 'other',
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
            resumeStage: 'stage4',
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );
    return db;
  }

  Future<({FakeDataEntryApi api, String? Function() landedPath})> pumpStage4(
    WidgetTester tester, {
    bool incomeSourcesFail = false,
  }) async {
    final sessionDb = await seedProfile();
    addTearDown(sessionDb.close);
    final referenceDb = ReferenceDatabase.forTesting();
    addTearDown(referenceDb.close);
    final api = FakeDataEntryApi();
    String? landedPath;

    final router = GoRouter(
      initialLocation: '/stage-4',
      routes: [
        GoRoute(path: '/stage-4', builder: (context, state) => const Stage4Screen()),
        GoRoute(
          path: '/stage-3',
          builder: (context, state) {
            landedPath = '/stage-3';
            return const Scaffold(body: Text('stage 3'));
          },
        ),
        GoRoute(
          path: '/stage-5',
          builder: (context, state) {
            landedPath = '/stage-5';
            return const Scaffold(body: Text('stage 5'));
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
          occupationItemsProvider.overrideWith((ref) => Stream.value(_occupationItems)),
          incomeSourceItemsProvider.overrideWith(
            (ref) => incomeSourcesFail
                ? Stream<List<ReferenceItem>>.error(Exception('offline'))
                : Stream.value(_incomeSourceItems),
          ),
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
    final (:api, :landedPath) = await pumpStage4(tester);

    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(api.submitStage4CallCount, 0);
    expect(find.textContaining('اختر المهنة'), findsOneWidget);
  });

  testWidgets('selecting two sources and marking one primary, then Next, submits correctly', (
    tester,
  ) async {
    final (:api, :landedPath) = await pumpStage4(tester);

    await tester.tap(find.widgetWithText(PickerField, 'المهنة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('مهندس'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(CheckboxListTile, 'راتب / أجر'));
    await tester.tap(find.widgetWithText(CheckboxListTile, 'معاش تقاعدي'));
    await tester.pumpAndSettle();

    // Exactly one primary — tap the radio next to the salary row.
    await tester.tap(find.byType(Radio<String>).first);
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).last, '50000');
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/stage-5');
    expect(api.submitStage4CallCount, 1);
    expect(api.lastStage4Args!['occupationCode'], '2');
    expect(api.lastStage4Args!['monthlyExpensesSdg'], '50000');

    // **This is the assertion that blocks walk comment 6a's dropdown conversion, and it is here
    // on purpose.** 6a asked for income source to become a dropdown like marital status,
    // education and gender did. It cannot: this control carries TWO independent pieces of state —
    // a SET of sources, and which ONE of them is primary — and a `DropdownButtonFormField` holds
    // a single value. A conversion would silently drop both capabilities while every existing
    // test above still passed, since they only check that Next advances.
    //
    // Product-owner decision 2026-09-08: keep the capability, compact the presentation instead.
    // Asserting the WIRE rather than the widget, because what matters is what the customer can
    // express, not which control expresses it — this stays true if the layout changes again.
    final sources = api.lastStage4Args!['incomeSources'] as List<IncomeSourceEntry>;
    expect(sources.map((e) => e.code), containsAll(<String>['RATIB', 'PENSION']));
    expect(sources.where((e) => e.primary).map((e) => e.code), ['RATIB']);
  });

  testWidgets('Next is blocked when a source is selected but none is marked primary', (
    tester,
  ) async {
    final (:api, :landedPath) = await pumpStage4(tester);

    await tester.tap(find.widgetWithText(PickerField, 'المهنة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('مهندس'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(CheckboxListTile, 'راتب / أجر'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).last, '50000');
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(api.submitStage4CallCount, 0);
  });

  testWidgets('selecting "other" reveals the free-text box, and Next is blocked until it is filled',
      (tester) async {
    final (:api, :landedPath) = await pumpStage4(tester);

    await tester.tap(find.widgetWithText(PickerField, 'المهنة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('مهندس'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(CheckboxListTile, 'أخرى'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(TextField, 'مصادر الدخل'), findsOneWidget);

    await tester.tap(find.byType(Radio<String>).first);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).last, '50000');
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull); // "otherText" still blank
    expect(api.submitStage4CallCount, 0);
  });

  testWidgets('a failed income-source list does not name the list — walk comment 2a', (
    tester,
  ) async {
    // Walk comment 2a: "it shouldn't say which list". Applied at the other two sites in batch one
    // and missed here, so this branch shipped saying «تعذر تحميل قائمة مصادر الدخل» — naming an
    // implementation detail the customer cannot act on, and pointing them at the field rather
    // than at their connection.
    //
    // Tested because this error branch had NO coverage at all, which is also why the miss
    // survived a whole batch: an untested branch leaves the coverage percentage unmoved rather
    // than lowering it, so nothing flagged it.
    await pumpStage4(tester, incomeSourcesFail: true);
    await tester.pumpAndSettle();

    expect(find.textContaining('قائمة مصادر الدخل'), findsNothing);
    expect(
      find.text('تعذر الاتصال، الرجاء التأكد من الاتصال بالإنترنت ثم أعد المحاولة'),
      findsOneWidget,
    );
  });

  testWidgets('the monthly expenses field strips non-digit characters', (tester) async {
    await pumpStage4(tester);

    final field = find.ancestor(
      of: find.text('النفقات الشهرية (جنيه سوداني)'),
      matching: find.byType(TextField),
    );
    await tester.enterText(field, 'abc123xyz٤٥');
    await tester.pumpAndSettle();

    expect(tester.widget<TextField>(field).controller!.text, '12345');
  });

  testWidgets('Back returns to /stage-3', (tester) async {
    final (:api, :landedPath) = await pumpStage4(tester);

    await tester.tap(find.widgetWithText(OutlinedButton, 'السابق'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/stage-3');
  });
}
