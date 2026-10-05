import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/text/arabic_noun_agreement.dart';

void main() {
  group('ArabicNounAgreement.minutes', () {
    test('1, 2, 3-10 and 11+ each take their own form', () {
      expect(ArabicNounAgreement.minutes.phrase(1), 'دقيقة واحدة');
      expect(ArabicNounAgreement.minutes.phrase(2), 'دقيقتان');
      expect(ArabicNounAgreement.minutes.phrase(3), '3 دقائق');
      expect(ArabicNounAgreement.minutes.phrase(10), '10 دقائق');
      expect(ArabicNounAgreement.minutes.phrase(11), '11 دقيقة');
      expect(ArabicNounAgreement.minutes.phrase(15), '15 دقيقة');
      expect(ArabicNounAgreement.minutes.phrase(60), '60 دقيقة');
    });
  });

  group('ArabicNounAgreement.children', () {
    test('1, 2, 3 and 11 each render the correct agreement form', () {
      expect(ArabicNounAgreement.children.phrase(1), 'طفل واحد');
      expect(ArabicNounAgreement.children.phrase(2), 'طفلان');
      expect(ArabicNounAgreement.children.phrase(3), '3 أطفال');
      expect(ArabicNounAgreement.children.phrase(11), '11 طفلاً');
    });
  });

  group('zero — the defect fixed 2026-09-07', () {
    // The bug: `phrase` branched on `if (n <= 10) return '$n $plural'`, so ZERO fell into the
    // 3-10 branch and rendered "0 أطفال" / "0 دقائق" — the plural Arabic reserves for 3 through
    // 10. Zero takes the same singular form as 11 and above.
    //
    // No revert-restore proof is offered for this one, and CLAUDE.md's rule is why: these are
    // DIRECT wrong-value assertions on the returned string, so they cannot pass against the
    // broken version — `'0 أطفال' != '0 طفلاً'` is decided by the assertion itself, not by
    // ordering or by an indirect effect.
    test('zero takes the singular form, not the 3-10 plural', () {
      expect(ArabicNounAgreement.children.phrase(0), '0 طفلاً');
      expect(ArabicNounAgreement.minutes.phrase(0), '0 دقيقة');
      expect(ArabicNounAgreement.hours.phrase(0), '0 ساعة');
    });

    test('zero is not rendered with the 3-10 plural', () {
      expect(ArabicNounAgreement.children.phrase(0), isNot('0 أطفال'));
      expect(ArabicNounAgreement.minutes.phrase(0), isNot('0 دقائق'));
    });

    test('a negative count trips the assert — it is a caller bug, never data', () {
      // Every production caller already guards on `n < 1`, so this asserts the guard that
      // surfaces a NEW unguarded caller during development. In a release build the assert is
      // stripped and the value degrades to the same grammatical singular rather than crashing a
      // customer inside a mandated journey.
      expect(() => ArabicNounAgreement.children.phrase(-1), throwsA(isA<AssertionError>()));
    });
  });
}
