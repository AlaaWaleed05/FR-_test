/// Arabic numeral-noun (معدود) agreement: 1, 2, 3-10 and 11+ each take a different form. Extracted
/// from `ChannelVerificationScreen._minutesPhrase` (S5-04, found under review: a plain `'$n دقيقة'`
/// is wrong for 3-10) and generalised so Stage 3's children-count field reuses the same rule rather
/// than a second hand-written copy (CLAUDE.md: "reuse or generalise it rather than writing a second").
///
/// Only four forms are needed because Arabic's own agreement rule collapses there: 1 and 2 have
/// dedicated singular/dual words with no number shown; 3-10 take the plural counted noun with the
/// number written before it; 11 and up revert to the SINGULAR noun (accusative-indefinite in full
/// classical grammar, simplified here to plain singular, matching `_minutesPhrase`'s own precedent)
/// written after the number.
class ArabicNounAgreement {
  const ArabicNounAgreement({
    required this.singular,
    required this.dual,
    required this.plural,
    required this.singularAfterEleven,
  });

  /// Used alone, for n == 1 (e.g. "دقيقة واحدة").
  final String singular;

  /// Used alone, for n == 2 (e.g. "دقيقتان").
  final String dual;

  /// Used as `'$n $plural'`, for 3 <= n <= 10 (e.g. "5 دقائق").
  final String plural;

  /// Used as `'$n $singularAfterEleven'`, for n >= 11 (e.g. "11 دقيقة").
  final String singularAfterEleven;

  /// DEFECT FIXED (2026-09-07, catalogued in the UI/UX design plan's "noticed in passing" 2):
  /// this previously read `if (n <= 10) return '$n $plural'`, so **zero and every negative fell
  /// into the 3-10 branch** and rendered with the wrong counted form — `phrase(0)` returned
  /// "0 أطفال", which takes the plural that Arabic reserves for 3 through 10.
  ///
  /// Zero takes the SAME singular form as 11 and above ("0 دقيقة"), which is why it needs no new
  /// wording of its own — the correct string is one the class already carries.
  ///
  /// A negative count is a programming error, never data: every caller already guards, so the
  /// assert is what surfaces a new unguarded caller during development. In release the assert is
  /// stripped, so negatives fall through to the same grammatical singular rather than crashing a
  /// customer mid-journey — a wrong number is recoverable, a crash in a mandated flow is not.
  String phrase(int n) {
    assert(n >= 0, 'ArabicNounAgreement.phrase does not take a negative count (got $n)');
    if (n == 1) return singular;
    if (n == 2) return dual;
    if (n >= 3 && n <= 10) return '$n $plural';
    return '$n $singularAfterEleven';
  }

  static const minutes = ArabicNounAgreement(
    singular: 'دقيقة واحدة',
    dual: 'دقيقتان',
    plural: 'دقائق',
    singularAfterEleven: 'دقيقة',
  );

  /// Stage 8's 24-hour scan block (customer.md "Policy values": "Block after scan or liveness
  /// exhaustion — 24 hours"), which is far too long to render in minutes.
  static const hours = ArabicNounAgreement(
    singular: 'ساعة واحدة',
    dual: 'ساعتان',
    plural: 'ساعات',
    singularAfterEleven: 'ساعة',
  );

  /// Stage 3's children-count field (field 16, docs/journeys/field-provenance.md).
  ///
  /// **No production caller since 2026-09-07.** D8.4 deleted Stage 3's live agreement label, which
  /// was its only use. Kept deliberately: the forms are correct, they are covered, and a counted
  /// noun for children is the likeliest one to be needed again — deleting tested, correct domain
  /// knowledge to satisfy a dead-code check would be a poor trade. `minutes` and `hours` remain
  /// live.
  static const children = ArabicNounAgreement(
    singular: 'طفل واحد',
    dual: 'طفلان',
    plural: 'أطفال',
    singularAfterEleven: 'طفلاً',
  );
}
