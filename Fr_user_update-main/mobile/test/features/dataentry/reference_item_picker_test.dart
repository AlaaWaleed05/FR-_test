import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/features/dataentry/reference_item_picker.dart';

/// Real seeded occupation-list labels, transcribed verbatim from
/// `backend/.../V0014__seed_occupation.sql` — not invented strings. `searchAr` is filled with
/// what `ref.ar_fold(label_ar)` produces for each label (the device never folds labels itself,
/// only the query — see `arFold`'s own doc comment), computed by hand against the SQL function's
/// documented transform (translate the hamza variants/ة/ى to ا/ه/ي, strip tatweel/harakat, then
/// lower — none of these labels contain digits, tatweel or harakat, so folding is just the
/// alef/haa/yaa normalisation).
final _occupationItems = [
  ReferenceItem(
    listCode: 'occupation',
    version: 1,
    itemCode: '97',
    labelAr: 'استاذ جامعي',
    labelEn: 'University Professor',
    searchAr: 'استاذ جامعي',
    searchEn: 'university professor',
    sortOrdinal: 1,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'occupation',
    version: 1,
    itemCode: '98',
    labelAr: 'استاذ مساعد',
    labelEn: 'Assistant Professor',
    searchAr: 'استاذ مساعد',
    searchEn: 'assistant professor',
    sortOrdinal: 2,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'occupation',
    version: 1,
    itemCode: '38',
    labelAr: 'دعاية واعلان',
    labelEn: 'Propaganda',
    searchAr: 'دعايه واعلان',
    searchEn: 'propaganda',
    sortOrdinal: 3,
    isActive: true,
  ),
  ReferenceItem(
    listCode: 'occupation',
    version: 1,
    itemCode: '2',
    labelAr: 'مهندس',
    labelEn: 'Engineer',
    searchAr: 'مهندس',
    searchEn: 'engineer',
    sortOrdinal: 4,
    isActive: true,
  ),
];

void main() {
  testWidgets('empty search shows the full list in the given (sort_ordinal) order', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: ReferenceItemPickerScreen(title: 'المهنة', items: _occupationItems),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final labels = tester
        .widgetList<Text>(find.descendant(of: find.byType(ListTile), matching: find.byType(Text)))
        .map((t) => t.data)
        .where((d) => d != null)
        .toList();
    expect(labels.first, 'استاذ جامعي');
  });

  testWidgets(
    'أ/ا equivalence: typing the hamza spelling "أستاذ" matches labels spelled with plain alef '
    '(real seeded items 97/98)',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Directionality(
            textDirection: TextDirection.rtl,
            child: ReferenceItemPickerScreen(title: 'المهنة', items: _occupationItems),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'أستاذ');
      await tester.pumpAndSettle();

      expect(find.text('استاذ جامعي'), findsOneWidget);
      expect(find.text('استاذ مساعد'), findsOneWidget);
      expect(find.text('دعاية واعلان'), findsNothing);
      expect(find.text('مهندس'), findsNothing);
    },
  );

  testWidgets(
    'ة/ه equivalence: typing the informal haa spelling "دعايه" matches the label spelled with '
    'taa marbuta (real seeded item 38)',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Directionality(
            textDirection: TextDirection.rtl,
            child: ReferenceItemPickerScreen(title: 'المهنة', items: _occupationItems),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'دعايه');
      await tester.pumpAndSettle();

      expect(find.text('دعاية واعلان'), findsOneWidget);
      expect(find.text('استاذ جامعي'), findsNothing);
    },
  );

  testWidgets('a query matching nothing shows the no-results state', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: ReferenceItemPickerScreen(title: 'المهنة', items: _occupationItems),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'زلزلة');
    await tester.pumpAndSettle();

    expect(find.textContaining('لا توجد نتائج'), findsOneWidget);
  });

  testWidgets('tapping an item pops the picker with that item, the code never displayed', (
    tester,
  ) async {
    ReferenceItem? popped;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Directionality(
            textDirection: TextDirection.rtl,
            child: Scaffold(
              body: Center(
                child: ElevatedButton(
                  onPressed: () async {
                    popped = await ReferenceItemPickerScreen.open(
                      context,
                      title: 'المهنة',
                      items: _occupationItems,
                    );
                  },
                  child: const Text('افتح'),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('افتح'));
    await tester.pumpAndSettle();

    expect(find.text('97'), findsNothing);
    await tester.tap(find.text('مهندس'));
    await tester.pumpAndSettle();

    expect(popped?.itemCode, '2');
  });
}
