import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/dataentry/data_entry_providers.dart';
import 'package:mobile/core/theme/app_theme.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart' show BackendUnreachableException;
import 'package:mobile/core/reference/reference_providers.dart';
import 'package:mobile/features/dataentry/reference_item_picker.dart';
import 'package:mobile/features/dataentry/stage3_screen.dart';

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
];

final _educationLevelItems = List.generate(
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


/// Selects [option] from the dropdown labelled [fieldLabel].
///
/// S8-05 turned gender, marital status and education from radio COLUMNS into dropdowns (walk
/// comment 6a, "this makes things more compact"). A test can no longer tap an option directly,
/// because the options do not exist in the tree until the menu is open — which is also the reason
/// the gendered-label assertions below had to move inside an opened menu rather than being
/// weakened.
Future<void> _pickDropdown(WidgetTester tester, String fieldLabel, String option) async {
  final field = find
      .ancestor(of: find.text(fieldLabel), matching: find.byType(InputDecorator))
      .first;
  await tester.ensureVisible(field);
  await tester.pumpAndSettle();
  await tester.tap(field);
  await tester.pumpAndSettle();
  // `.last`: while the menu is open the selected value is also painted in the closed field, so
  // the option text can legitimately match twice.
  await tester.tap(find.text(option).last);
  await tester.pumpAndSettle();
}

/// Opens the dropdown labelled [fieldLabel] WITHOUT choosing anything, so a test can assert on
/// the options it offers.
Future<void> _openDropdown(WidgetTester tester, String fieldLabel) async {
  final field = find
      .ancestor(of: find.text(fieldLabel), matching: find.byType(InputDecorator))
      .first;
  await tester.ensureVisible(field);
  await tester.pumpAndSettle();
  await tester.tap(field);
  await tester.pumpAndSettle();
}

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
            resumeStage: 'stage3',
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

  Future<({FakeDataEntryApi api, String? Function() landedPath})> pumpStage3(
    WidgetTester tester,
  ) async {
    final sessionDb = await seedProfile();
    addTearDown(sessionDb.close);
    final referenceDb = await seedAdminDivisionRoot();
    addTearDown(referenceDb.close);
    final api = FakeDataEntryApi();
    String? landedPath;

    final router = GoRouter(
      initialLocation: '/stage-3',
      routes: [
        GoRoute(path: '/stage-3', builder: (context, state) => const Stage3Screen()),
        GoRoute(
          path: '/stage-4',
          builder: (context, state) {
            landedPath = '/stage-4';
            return const Scaffold(body: Text('stage 4'));
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
          educationLevelItemsProvider.overrideWith((ref) => Stream.value(_educationLevelItems)),
        ],
        child: MaterialApp.router(
          // The real theme AND the real localisation setup, because this file now asserts how an
          // OPEN dropdown menu renders — a menu lives in the Navigator's overlay, so its surface
          // and its direction come from the app shell rather than from the screen.
          theme: AppTheme.light(),
          locale: const Locale('ar'),
          supportedLocales: const [Locale('ar')],
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (api: api, landedPath: () => landedPath);
  }

  testWidgets('an OPEN dropdown menu stays RTL and takes the white container surface', (
    tester,
  ) async {
    // Walk comment 6a turned these questions into dropdowns in batch one, and they shipped with
    // no test that ever OPENED one. Two things are worth pinning, and both live in the overlay
    // rather than on the screen:
    //
    //  1. **Direction.** A dropdown menu is pushed into the Navigator's overlay, not built inside
    //     the screen's subtree, so it does not inherit a `Directionality` that a screen wrapped
    //     around itself. This app sets RTL twice over — `locale: ar` through `Localizations` and
    //     an explicit builder-level wrap — and this asserts the menu actually lands RTL, which is
    //     what puts the item text and the check on the correct sides for Arabic.
    //  2. **Surface.** Material has no theme slot for this menu; left alone it paints
    //     `canvasColor` (#F9F9FF, 1.17:1 against the canvas) while the CLOSED field is a white
    //     container — one control disagreeing with itself. `AppTheme.dropdownMenuSurface` is
    //     passed at each site, and this proves it reaches the rendered menu.
    await pumpStage3(tester);

    await tester.tap(find.text('النوع'));
    await tester.pumpAndSettle();

    expect(find.text('ذكر'), findsWidgets);

    final menuItem = find.text('ذكر').last;
    expect(Directionality.of(tester.element(menuItem)), TextDirection.rtl);

    // The surface half. Stated precisely: this reads the `dropdownColor` argument back off the
    // CLOSED button, which is not the same as sampling the overlay `Material` that paints the
    // open menu — it proves the value is supplied, not that Flutter honours it (`dropdown.dart`
    // is what does that, and it is the framework's contract, not this app's).
    //
    // It is still a real guard rather than a tautology: `dropdownColor` was null before this
    // change, so this fails against the previous code. The RTL assertion above does NOT have
    // that property — RTL was already correct — so it characterises existing behaviour and would
    // only ever catch a future regression.
    final dropdown = tester.widget<DropdownButton<String>>(
      find.byType(DropdownButton<String>).first,
    );
    expect(dropdown.dropdownColor, AppTheme.dropdownMenuSurface(AppTheme.light().colorScheme));
    expect(dropdown.dropdownColor, isNot(AppTheme.light().colorScheme.surface));
  });

  /// **Walk comment 3.** This test previously asserted `findsNWidgets(1)` with the note
  /// "birth country field only, residence unset" — it was pinning the DEFECT in place. Both
  /// country pickers on this screen now default to Sudan, so the count is 2, and the assertion
  /// is rewritten to name the fields rather than count anonymous text so it cannot drift again.
  testWidgets('defaults BOTH country pickers to Sudan when no draft exists yet', (tester) async {
    await pumpStage3(tester);

    String? valueOf(String label) => tester
        .widgetList<PickerField>(find.byType(PickerField))
        .firstWhere((f) => f.label == label)
        .value;

    expect(valueOf('المواطنة'), 'السودان');
    expect(valueOf('بلد الميلاد'), 'السودان');
  });

  testWidgets(
    'no gendered marital-status label renders before a sex is chosen (S5-05 review: null was '
    'being treated as female)',
    (tester) async {
      await pumpStage3(tester);

      expect(find.text('عزباء'), findsNothing);
      expect(find.text('أعزب'), findsNothing);
      expect(find.text('متزوجة'), findsNothing);
      expect(find.text('متزوج'), findsNothing);
      expect(find.text('اختر النوع أولاً لعرض الحالة الاجتماعية.'), findsOneWidget);
    },
  );

  testWidgets('selecting sex="m" then married shows "اسم الزوجة" (wife\'s name), not '
      '"اسم الزوج"', (tester) async {
    await pumpStage3(tester);

    await _pickDropdown(tester, 'النوع', 'ذكر');
    await _pickDropdown(tester, 'الحالة الاجتماعية', 'متزوج');

    expect(find.widgetWithText(TextField, 'اسم الزوجة'), findsOneWidget);
    expect(find.widgetWithText(TextField, 'اسم الزوج'), findsNothing);
  });

  testWidgets('selecting sex="f" then married shows "اسم الزوج" (husband\'s name), and the '
      'marital-status label itself is feminine ("متزوجة")', (tester) async {
    await pumpStage3(tester);

    await _pickDropdown(tester, 'النوع', 'أنثى');

    // The gendered labels now live in the dropdown's menu, so proving they are FEMININE means
    // opening it. The property under test is unchanged, and is asserted more tightly than before:
    // the masculine forms must be absent, not merely the feminine ones present.
    await _openDropdown(tester, 'الحالة الاجتماعية');
    expect(find.text('متزوجة'), findsWidgets);
    expect(find.text('عزباء'), findsWidgets);
    expect(find.text('متزوج'), findsNothing);
    expect(find.text('أعزب'), findsNothing);

    await tester.tap(find.text('متزوجة').last);
    await tester.pumpAndSettle();

    expect(find.widgetWithText(TextField, 'اسم الزوج'), findsOneWidget);
  });

  testWidgets(
    'the children-count field carries NO live agreement label (D8.4, 2026-09-07)',
    (tester) async {
      // The screen used to render «عدد الأطفال: طفل واحد» beneath this field, rebuilt on every
      // keystroke. D8.4 deleted it, and the deletion paid twice: it removed the numeral-agreement
      // hazard from the screen, and it removed the only reason the screen called `setState` on
      // every character of all five text controllers — a full layout per keystroke on a 2 GB
      // device. The agreement RULE itself is still tested, at
      // test/core/text/arabic_noun_agreement_test.dart, where it belongs.
      await pumpStage3(tester);
      await _pickDropdown(tester, 'النوع', 'ذكر');
      await _pickDropdown(tester, 'الحالة الاجتماعية', 'متزوج');
      final yesTile = find.widgetWithText(RadioListTile<bool>, 'نعم');
      await tester.ensureVisible(yesTile);
      await tester.pumpAndSettle();
      await tester.tap(yesTile);
      await tester.pumpAndSettle();

      final field = find.widgetWithText(TextField, 'عدد الأطفال');
      await tester.ensureVisible(field);
      await tester.pumpAndSettle();

      // The field still takes the value — only the derived label went.
      await tester.enterText(field, '1');
      await tester.pumpAndSettle();
      expect(find.widgetWithText(TextField, 'عدد الأطفال'), findsOneWidget);
      expect(find.textContaining('طفل واحد'), findsNothing);

      // Deliberately the AGREEMENT phrase, not the bare word: «أطفال» also appears in the field's
      // own label «عدد الأطفال» and in the question «هل لديك أطفال؟», both of which stay.
      await tester.enterText(field, '3');
      await tester.pumpAndSettle();
      expect(find.textContaining('3 أطفال'), findsNothing);
    },
  );

  testWidgets('choosing "single" hides the spouse/children fields entirely', (tester) async {
    await pumpStage3(tester);
    await _pickDropdown(tester, 'النوع', 'ذكر');
    await _pickDropdown(tester, 'الحالة الاجتماعية', 'أعزب');

    expect(find.widgetWithText(TextField, 'اسم الزوجة'), findsNothing);
    expect(find.text('هل لديك أطفال؟'), findsNothing);
  });

  testWidgets('birth state uses a Sudan-filtered picker while birth country is Sudan', (
    tester,
  ) async {
    await pumpStage3(tester);

    final birthStateField = find.widgetWithText(PickerField, 'ولاية الميلاد');
    await tester.ensureVisible(birthStateField);
    await tester.pumpAndSettle();
    await tester.tap(birthStateField);
    await tester.pumpAndSettle();

    expect(find.text('الخرطوم'), findsOneWidget);
  });

  /// Fills every mandatory Stage 3 field with a minimal valid answer, without tapping "Next" —
  /// shared by the online-success test and the offline-queue test below.
  Future<void> fillMinimalValidStage3(WidgetTester tester) async {
    await _pickDropdown(tester, 'النوع', 'ذكر');
    await tester.enterText(find.widgetWithText(TextField, 'الجنس (العرق)'), 'عربي');
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(PickerField, 'المواطنة'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('السودان'));
    await tester.pumpAndSettle();

    await _pickDropdown(tester, 'الحالة الاجتماعية', 'أعزب');

    await _pickDropdown(tester, 'المستوى التعليمي', 'مستوى 3');

    final birthStateField = find.widgetWithText(PickerField, 'ولاية الميلاد');
    await tester.ensureVisible(birthStateField);
    await tester.pumpAndSettle();
    await tester.tap(birthStateField);
    await tester.pumpAndSettle();
    await tester.tap(find.text('الخرطوم'));
    await tester.pumpAndSettle();

    final birthCityField = find.widgetWithText(TextField, 'مدينة الميلاد');
    await tester.ensureVisible(birthCityField);
    await tester.pumpAndSettle();
    await tester.enterText(birthCityField, 'أم درمان');
    await tester.pumpAndSettle();
  }

  Future<void> tapNext(WidgetTester tester) async {
    final nextButton = find.widgetWithText(FilledButton, 'التالي');
    await tester.ensureVisible(nextButton);
    await tester.pumpAndSettle();
    await tester.tap(nextButton);
    await tester.pumpAndSettle();
  }

  testWidgets('a full valid submission navigates to /stage-4', (tester) async {
    final (:api, :landedPath) = await pumpStage3(tester);

    await fillMinimalValidStage3(tester);
    await tapNext(tester);

    expect(landedPath(), '/stage-4');
    expect(api.submitStage3CallCount, 1);
    expect(api.lastStage3Args!['sexDeclared'], 'm');
    expect(api.lastStage3Args!['ethnicity'], 'عربي');
    expect(api.lastStage3Args!['maritalStatus'], 'single');
    expect(api.lastStage3Args!['educationLevel'], 3);
    expect(api.lastStage3Args!['birthStateCode'], '31');
    expect(api.lastStage3Args!['birthCityText'], 'أم درمان');
  });

  testWidgets(
    'a BackendUnreachableException still advances local progress and navigates to /stage-4 — '
    'offline-capable, free navigation (S5-05 review: this behaviour was untested)',
    (tester) async {
      final (:api, :landedPath) = await pumpStage3(tester);
      api.stage3ErrorToThrow = const BackendUnreachableException('offline');

      await fillMinimalValidStage3(tester);
      await tapNext(tester);

      expect(landedPath(), '/stage-4');
      expect(api.submitStage3CallCount, 1);
    },
  );
}
