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
import 'package:mobile/features/dataentry/stage3_screen.dart';
import 'package:mobile/features/dataentry/stage4_screen.dart';
import 'package:mobile/features/dataentry/stage5_screen.dart';
import 'package:mobile/features/dataentry/stage6_screen.dart';

import '../../core/dataentry/fake_data_entry_api.dart';

/// Proves customer.md's free back-navigation across stages 3-6: "Back-navigation from stage 6 to
/// stage 3 with values intact, edited, and the edit surviving forward navigation" (S5-05's
/// required proof). Drives the REAL four screens through a REAL shared `SessionDatabase` via one
/// router — data surviving navigation is exactly what a shared, persisted `DataEntryDraft` row
/// means, not something this test fakes.
void main() {
  final countryItems = [
    ReferenceItem(
      listCode: 'country',
      version: 1,
      itemCode: 'SD',
      labelAr: 'السودان',
      searchAr: 'السودان',
      sortOrdinal: 1,
      isActive: true,
    ),
  ];
  final adminDivisionItems = [
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
  final educationLevelItems = List.generate(
    7,
    (i) => ReferenceItem(
      listCode: 'education_level',
      version: 1,
      itemCode: '${i + 1}',
      labelAr: 'مستوى ${i + 1}',
      searchAr: 'مستوى ${i + 1}',
      sortOrdinal: i + 1,
      isActive: true,
    ),
  );
  final occupationItems = [
    ReferenceItem(
      listCode: 'occupation',
      version: 1,
      itemCode: '2',
      labelAr: 'مهندس',
      searchAr: 'مهندس',
      sortOrdinal: 1,
      isActive: true,
    ),
  ];
  final incomeSourceItems = [
    ReferenceItem(
      listCode: 'income_source',
      version: 1,
      itemCode: 'RATIB',
      labelAr: 'راتب / أجر',
      searchAr: 'راتب / اجر',
      sortOrdinal: 1,
      isActive: true,
    ),
  ];

  testWidgets(
    'stage3 -> 4 -> 5 -> 6, back to stage3, edit ethnicity, forward again — the edit survives '
    'and is what stage3 actually resubmits',
    (tester) async {
      final sessionDb = SessionDatabase.forTesting();
      addTearDown(sessionDb.close);
      await sessionDb
          .into(sessionDb.localProgress)
          .insertOnConflictUpdate(
            LocalProgressCompanion.insert(
              id: const Value(0),
              verifiedBranchCode: '16',
              verifiedAccountNumber: '0000000001',
              resumeStage: 'stage3',
              profileId: const Value('p1'),
              updatedAt: DateTime.now(),
            ),
          );
      final referenceDb = ReferenceDatabase.forTesting();
      addTearDown(referenceDb.close);
      await referenceDb
          .into(referenceDb.referenceLists)
          .insert(
            ReferenceListsCompanion.insert(
              listCode: 'admin_division',
              version: 1,
              contentHash: 'irrelevant',
              itemCount: adminDivisionItems.length,
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
      final api = FakeDataEntryApi();

      final router = GoRouter(
        initialLocation: '/stage-3',
        routes: [
          GoRoute(path: '/stage-3', builder: (context, state) => const Stage3Screen()),
          GoRoute(path: '/stage-4', builder: (context, state) => const Stage4Screen()),
          GoRoute(path: '/stage-5', builder: (context, state) => const Stage5Screen()),
          GoRoute(path: '/stage-6', builder: (context, state) => const Stage6Screen()),
          GoRoute(
            path: '/session-pending',
            builder: (context, state) => const Scaffold(body: Text('session pending')),
          ),
        ],
      );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sessionDatabaseProvider.overrideWithValue(sessionDb),
            referenceDatabaseProvider.overrideWithValue(referenceDb),
            dataEntryApiProvider.overrideWithValue(api),
            countryItemsProvider.overrideWith((ref) => Stream.value(countryItems)),
            adminDivisionItemsProvider.overrideWith((ref) => Stream.value(adminDivisionItems)),
            educationLevelItemsProvider.overrideWith((ref) => Stream.value(educationLevelItems)),
            occupationItemsProvider.overrideWith((ref) => Stream.value(occupationItems)),
            incomeSourceItemsProvider.overrideWith((ref) => Stream.value(incomeSourceItems)),
          ],
          child: MaterialApp.router(
            routerConfig: router,
            builder: (context, child) =>
                Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      );
      await tester.pumpAndSettle();

      Future<void> tapVisible(Finder finder) async {
        await tester.ensureVisible(finder);
        await tester.pumpAndSettle();
        await tester.tap(finder);
        await tester.pumpAndSettle();
      }

      /// S8-05 (walk comment 6a): gender, marital status and education are dropdowns now, so the
      /// option has to be opened before it can be tapped — it does not exist in the tree until
      /// the menu is up.
      Future<void> pickDropdown(String fieldLabel, String option) async {
        final field = find
            .ancestor(of: find.text(fieldLabel), matching: find.byType(InputDecorator))
            .first;
        await tester.ensureVisible(field);
        await tester.pumpAndSettle();
        await tester.tap(field);
        await tester.pumpAndSettle();
        await tester.tap(find.text(option).last);
        await tester.pumpAndSettle();
      }

      Future<void> enterVisible(Finder fieldFinder, String text) async {
        await tester.ensureVisible(fieldFinder);
        await tester.pumpAndSettle();
        await tester.enterText(fieldFinder, text);
        await tester.pumpAndSettle();
      }

      // --- Stage 3: fill and advance ---
      await pickDropdown('النوع', 'ذكر');
      await enterVisible(find.widgetWithText(TextField, 'الجنس (العرق)'), 'عربي');
      await tapVisible(find.widgetWithText(PickerField, 'المواطنة'));
      await tapVisible(find.text('السودان'));
      await pickDropdown('الحالة الاجتماعية', 'أعزب');
      await pickDropdown('المستوى التعليمي', 'مستوى 3');
      await tapVisible(find.widgetWithText(PickerField, 'ولاية الميلاد'));
      await tapVisible(find.text('الخرطوم'));
      await enterVisible(find.widgetWithText(TextField, 'مدينة الميلاد'), 'أم درمان');
      await tapVisible(find.widgetWithText(FilledButton, 'التالي'));

      expect(find.byType(Stage4Screen), findsOneWidget);

      // --- Stage 4: fill and advance ---
      await tapVisible(find.widgetWithText(PickerField, 'المهنة'));
      await tapVisible(find.text('مهندس'));
      await tapVisible(find.widgetWithText(CheckboxListTile, 'راتب / أجر'));
      await tapVisible(find.byType(Radio<String>).first);
      await enterVisible(find.byType(TextField).last, '50000');
      await tapVisible(find.widgetWithText(FilledButton, 'التالي'));

      expect(find.byType(Stage5Screen), findsOneWidget);

      // --- Stage 5: fill and advance ---
      await tapVisible(find.widgetWithText(PickerField, 'الولاية'));
      await tapVisible(find.text('الخرطوم'));
      await tapVisible(find.widgetWithText(PickerField, 'المحلية / المحافظة'));
      await tapVisible(find.text('الخرطوم بحري'));
      for (final label in ['المدينة', 'المنطقة', 'الشارع', 'المربع', 'رقم المنزل']) {
        await enterVisible(find.widgetWithText(TextField, label), 'قيمة');
      }
      await tapVisible(find.widgetWithText(FilledButton, 'التالي'));

      expect(find.byType(Stage6Screen), findsOneWidget);

      // --- Stage 6: back to stage 5, back to stage 4, back to stage 3 ---
      await tapVisible(find.widgetWithText(OutlinedButton, 'السابق'));
      expect(find.byType(Stage5Screen), findsOneWidget);
      await tapVisible(find.widgetWithText(OutlinedButton, 'السابق'));
      expect(find.byType(Stage4Screen), findsOneWidget);
      await tapVisible(find.widgetWithText(OutlinedButton, 'السابق'));
      expect(find.byType(Stage3Screen), findsOneWidget);

      // Values from the original stage 3 fill-in are still there.
      expect(find.widgetWithText(TextField, 'الجنس (العرق)'), findsOneWidget);
      final ethnicityField = tester.widget<TextField>(
        find.widgetWithText(TextField, 'الجنس (العرق)'),
      );
      expect(ethnicityField.controller!.text, 'عربي');

      // --- Edit ethnicity, then forward all the way back to stage 6's own Next ---
      await enterVisible(find.widgetWithText(TextField, 'الجنس (العرق)'), 'عربي مُعدَّل');
      await tapVisible(find.widgetWithText(FilledButton, 'التالي'));
      expect(find.byType(Stage4Screen), findsOneWidget);

      // Stage 4's own values survived being left and returned to.
      expect(find.text('مهندس'), findsOneWidget);
      await tapVisible(find.widgetWithText(FilledButton, 'التالي'));
      expect(find.byType(Stage5Screen), findsOneWidget);
      await tapVisible(find.widgetWithText(FilledButton, 'التالي'));
      expect(find.byType(Stage6Screen), findsOneWidget);

      // Stage 3 was resubmitted a second time (its own "Next" always resubmits — once for the
      // original pass, once for this edit-and-forward pass) with the EDITED value — the edit
      // survived forward navigation all the way through, proven by what was actually sent over
      // the wire, not just what the field displays.
      expect(api.submitStage3CallCount, 2);
      expect(api.lastStage3Args!['ethnicity'], 'عربي مُعدَّل');
    },
  );
}
