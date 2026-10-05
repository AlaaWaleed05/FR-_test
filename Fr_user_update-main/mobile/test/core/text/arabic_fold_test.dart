import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/text/arabic_fold.dart';

void main() {
  group('arFold', () {
    test('folds all four hamza-bearing alef variants to plain alef', () {
      expect(arFold('أستاذ'), arFold('استاذ'));
      expect(arFold('إستاذ'), arFold('استاذ'));
      expect(arFold('آستاذ'), arFold('استاذ'));
      expect(arFold('ٱستاذ'), arFold('استاذ'));
    });

    test('folds taa marbuta to haa', () {
      expect(arFold('دعاية'), arFold('دعايه'));
    });

    test('folds alef maksura to yaa', () {
      expect(arFold('مستشفى'), arFold('مستشفي'));
    });

    test('strips tatweel', () {
      expect(arFold('مـهـنـدس'), 'مهندس');
    });

    test('strips harakat and the superscript alef', () {
      expect(arFold('مُهَنْدِسٌ'), 'مهندس');
      expect(arFold('رَحْمَٰن'), 'رحمن');
    });

    test('strips zero-width and bidi control characters', () {
      // Built via String.fromCharCode, never a literal invisible code point in source — a
      // literal bidi/zero-width character anywhere in source (test data included) is itself a
      // text-spoofing risk `flutter analyze` flags.
      final zeroWidthJoined = 'مهندس${String.fromCharCode(0x200B)}مهندس';
      expect(arFold(zeroWidthJoined), 'مهندسمهندس');
      final bidiWrapped = '${String.fromCharCode(0x202A)}مهندس${String.fromCharCode(0x202C)}';
      expect(arFold(bidiWrapped), 'مهندس');
    });

    test('folds Arabic-Indic and Extended Arabic-Indic digits to ASCII', () {
      expect(arFold('١٢٣'), '123');
      expect(arFold('۱۲۳'), '123');
    });

    test('lowercases ASCII', () {
      expect(arFold('Test'), 'test');
    });

    test('is a no-op on an already-folded string', () {
      expect(arFold('مهندس'), 'مهندس');
    });

    test('real seeded occupation labels: hamza-alef equivalence (item 97/98)', () {
      // V0014__seed_occupation.sql: ('97','استاذ جامعي', ...), ('98','استاذ مساعد', ...) — spelled
      // with plain alef. A customer typing the orthographically "correct" hamza form must still
      // match.
      final query = arFold('أستاذ');
      expect(arFold('استاذ جامعي'), contains(query));
      expect(arFold('استاذ مساعد'), contains(query));
    });

    test('real seeded occupation label: taa-marbuta/haa equivalence (item 38)', () {
      // V0014__seed_occupation.sql: ('38','دعاية واعلان', ...) — spelled with taa marbuta. A
      // customer typing the common informal haa spelling must still match.
      final query = arFold('دعايه');
      expect(arFold('دعاية واعلان'), contains(query));
    });
  });
}
