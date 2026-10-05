import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/dataentry/data_entry_providers.dart';
import 'package:mobile/core/forms/field_error_state.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/reference/reference_providers.dart';
import 'package:mobile/features/dataentry/reference_item_picker.dart';
import 'package:mobile/features/dataentry/stage5_screen.dart';

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
  ReferenceItem(
    listCode: 'country',
    version: 1,
    itemCode: 'EG',
    labelAr: 'مصر',
    labelEn: 'Egypt',
    searchAr: 'مصر',
    searchEn: 'egypt',
    sortOrdinal: 2,
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
    itemCode: '41',
    parentCode: 'SD',
    labelAr: 'الجزيرة',
    searchAr: 'الجزيرة',
    sortOrdinal: 3,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'admin_division',
    version: 1,
    itemCode: '3101',
    parentCode: '31',
    labelAr: 'الخرطوم بحري',
    searchAr: 'الخرطوم بحري',
    sortOrdinal: 4,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'admin_division',
    version: 1,
    itemCode: '4101',
    parentCode: '41',
    labelAr: 'ود مدني',
    searchAr: 'ود مدني',
    sortOrdinal: 5,
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
            resumeStage: 'stage5',
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

  /// [bottomInset] simulates a 3-button Android navigation bar (walk comment 9, 2026-09-10).
  /// Zero by default, which is what the test binding supplies and what let the defect ship.
  Future<({FakeDataEntryApi api, String? Function() landedPath})> pumpStage5(
    WidgetTester tester, {
    double bottomInset = 0,
  }) async {
    final sessionDb = await seedProfile();
    addTearDown(sessionDb.close);
    final referenceDb = await seedAdminDivisionRoot();
    addTearDown(referenceDb.close);
    final api = FakeDataEntryApi();
    String? landedPath;

    final router = GoRouter(
      initialLocation: '/stage-5',
      routes: [
        GoRoute(path: '/stage-5', builder: (context, state) => const Stage5Screen()),
        GoRoute(
          path: '/stage-4',
          builder: (context, state) {
            landedPath = '/stage-4';
            return const Scaffold(body: Text('stage 4'));
          },
        ),
        GoRoute(
          path: '/stage-6',
          builder: (context, state) {
            landedPath = '/stage-6';
            return const Scaffold(body: Text('stage 6'));
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
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(
              padding: EdgeInsets.only(bottom: bottomInset),
            ),
            child: Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (api: api, landedPath: () => landedPath);
  }

  /// **The screen-level guard for walk comment 9 (2026-09-10).** `stage_action_bar_test.dart`
  /// proves the WIDGET insets correctly; this proves THIS SCREEN actually uses it. Raised by
  /// `@agent-reviewer`: without a test at this level, reverting `Stage5Screen`'s action row to a
  /// bare `Padding` reproduces the defect with the whole suite still green — which is precisely
  /// how the defect shipped in the first place.
  ///
  /// Stage 5 stands for the nine `StageActionBar` adopters; `stage8_screen_test.dart` carries the
  /// matching guard for the four body-level `SafeArea` screens.
  testWidgets('walk comment 9 — «التالي» clears the system navigation bar', (tester) async {
    await pumpStage5(tester, bottomInset: 48);

    final screenBottom = tester.getSize(find.byType(MaterialApp)).height;
    final next = find.widgetWithText(FilledButton, 'التالي');
    await tester.ensureVisible(next);
    await tester.pumpAndSettle();

    expect(
      screenBottom - tester.getRect(next).bottom,
      greaterThanOrEqualTo(48),
      reason: 'the button must sit above the navigation bar, not underneath it',
    );
  });

  testWidgets('defaults the country to Sudan when no draft exists yet', (tester) async {
    await pumpStage5(tester);
    expect(find.text('السودان'), findsOneWidget);
  });

  testWidgets('changing the state clears any already-selected locality (cascade reset)', (
    tester,
  ) async {
    await pumpStage5(tester);

    await tester.tap(find.widgetWithText(PickerField, 'الولاية'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(PickerField, 'المحلية / المحافظة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم بحري'));
    await tester.pumpAndSettle();
    expect(find.text('الخرطوم بحري'), findsOneWidget);

    // Change the state — the locality picker's own filtered list only shows children of the
    // NEW state, and the previously chosen locality must no longer be shown as selected.
    await tester.tap(find.widgetWithText(PickerField, 'الولاية'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الجزيرة'));
    await tester.pumpAndSettle();

    expect(find.text('الخرطوم بحري'), findsNothing);

    // The locality picker for the new state offers only ITS children.
    await tester.tap(find.widgetWithText(PickerField, 'المحلية / المحافظة'));
    await tester.pumpAndSettle();
    expect(find.text('ود مدني'), findsOneWidget);
    expect(find.text('الخرطوم بحري'), findsNothing);
  });

  /// **The invariant the keyboard chain rests on, asserted on a REAL screen (walk comment 2).**
  /// `textFieldOrder` is computed, so it can name a field that is not currently built — and a
  /// chain pointing at an absent field is exactly the dead next key the comment reports. Every
  /// entry must resolve to a focus node attached to a live widget, in BOTH country branches.
  ///
  /// Raised by `@agent-reviewer`, which found the first version of this order omitted the two
  /// non-Sudan free-text fields entirely, so the keyboard jumped over them.
  testWidgets('every entry in the keyboard order is a field that is actually on screen', (
    tester,
  ) async {
    await pumpStage5(tester);
    // The mixin is generic (`on State<T>`), so it needs its type argument to be named as a type.
    final state =
        tester.state<State<Stage5Screen>>(find.byType(Stage5Screen))
            as FieldErrorState<Stage5Screen>;

    void assertOrderIsLive(String branch) {
      expect(state.textFieldOrder, isNotEmpty, reason: branch);
      for (final field in state.textFieldOrder) {
        expect(
          state.fieldNode(field).context,
          isNotNull,
          reason: '$branch: «$field» is in the order but not built',
        );
      }
      // And the last entry is the one carrying `done`, so the chain ends where the form ends.
      expect(state.fieldAction(state.textFieldOrder.last), TextInputAction.done, reason: branch);
    }

    assertOrderIsLive('Sudan');
    expect(state.textFieldOrder, isNot(contains('stateText')));

    await tester.tap(find.widgetWithText(PickerField, 'البلد'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('مصر'));
    await tester.pumpAndSettle();

    assertOrderIsLive('non-Sudan');
    expect(
      state.textFieldOrder,
      containsAllInOrder(['stateText', 'localityText', 'city']),
      reason: 'the two mandatory free-text fields come BEFORE the address block, as laid out',
    );
  });

  testWidgets('selecting a non-Sudan country falls back to free-text state/locality', (
    tester,
  ) async {
    await pumpStage5(tester);

    await tester.tap(find.widgetWithText(PickerField, 'البلد'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('مصر'));
    await tester.pumpAndSettle();

    expect(find.widgetWithText(TextField, 'الولاية'), findsOneWidget);
    expect(find.widgetWithText(TextField, 'المحلية / المحافظة'), findsOneWidget);
    expect(find.byType(PickerField), findsOneWidget); // country field only
  });

  testWidgets('Next submits with the full Sudan cascade and free-text fields filled', (
    tester,
  ) async {
    final (:api, :landedPath) = await pumpStage5(tester);

    await tester.tap(find.widgetWithText(PickerField, 'الولاية'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(PickerField, 'المحلية / المحافظة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم بحري'));
    await tester.pumpAndSettle();

    for (final label in ['المدينة', 'المنطقة', 'الشارع', 'المربع', 'رقم المنزل']) {
      await tester.enterText(find.widgetWithText(TextField, label), 'قيمة');
      await tester.pumpAndSettle();
    }

    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/stage-6');
    expect(api.submitStage5CallCount, 1);
    expect(api.lastStage5Args!['countryCode'], 'SD');
    expect(api.lastStage5Args!['stateCode'], '31');
    expect(api.lastStage5Args!['localityCode'], '3101');
  });

  testWidgets('Back returns to /stage-4', (tester) async {
    final (:api, :landedPath) = await pumpStage5(tester);

    await tester.tap(find.widgetWithText(OutlinedButton, 'السابق'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/stage-4');
  });
}
