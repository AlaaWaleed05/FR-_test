/// Client-side counterpart of `ref.ar_fold()` (backend
/// `V0011__ref_registry_and_fold.sql`), transcribed character-for-character from that function's
/// `translate`/`regexp_replace` arguments — not re-derived from a general Arabic-normalisation
/// recipe, so it stays exactly as narrow (and exactly as permissive) as the function every
/// `search_ar`/`search_en` column on the wire was already folded through server-side.
///
/// Only ever applied to the user's typed QUERY (docs/components/reference-data.md client rule 6:
/// "The device folds only the query string" — item labels arrive already folded via
/// `searchAr`/`searchEn`, inside the hash envelope).
///
/// Known, deliberate gap shared with the SQL original (BL-014): a DECOMPOSED hamza (ا + combining
/// U+0654) is not folded to ا — `ar_fold` only maps the four PRECOMPOSED hamza-bearing letters
/// (أ إ آ ٱ). Matching the server's exact behaviour here, not exceeding it, is what "kept
/// consistent with ar_fold" means for this port.
String arFold(String input) {
  final translated = StringBuffer();
  for (final rune in input.runes) {
    translated.writeCharCode(_translate(rune));
  }
  final stripped = StringBuffer();
  for (final rune in translated.toString().runes) {
    if (!_isStripped(rune)) stripped.writeCharCode(rune);
  }
  return stripped.toString().toLowerCase();
}

/// Mirrors the SQL `translate()` call's two source/target strings exactly: the four
/// hamza-bearing alef variants and ة/ى fold to ا/ه/ي; Arabic-Indic (U+0660-U+0669) and Extended
/// Arabic-Indic (U+06F0-U+06F9) digits fold to ASCII.
int _translate(int rune) {
  switch (rune) {
    case 0x0623: // أ
    case 0x0625: // إ
    case 0x0622: // آ
    case 0x0671: // ٱ
      return 0x0627; // ا
    case 0x0629: // ة
      return 0x0647; // ه
    case 0x0649: // ى
      return 0x064A; // ي
  }
  if (rune >= 0x0660 && rune <= 0x0669) return rune - 0x0660 + 0x30; // Arabic-Indic
  if (rune >= 0x06F0 && rune <= 0x06F9) return rune - 0x06F0 + 0x30; // Extended Arabic-Indic
  return rune;
}

/// Mirrors the SQL regex character class (tatweel, harakat, the superscript alef, the Quranic
/// annotation-sign block, and the zero-width/bidi control characters) exactly, range for range —
/// see `V0011__ref_registry_and_fold.sql` for the source regex. Deliberately described here by
/// hex range rather than quoted verbatim: the class itself contains bidi-control and zero-width
/// code points, and `flutter analyze` flags a literal one anywhere in source, comments included,
/// as a text-spoofing risk.
bool _isStripped(int rune) {
  if (rune == 0x0640) return true; // ـ tatweel
  if (rune >= 0x064B && rune <= 0x0652) return true; // ً-ْ harakat
  if (rune == 0x0670) return true; // ٰ superscript alef
  if (rune >= 0x06D6 && rune <= 0x06ED) return true; // ۖ-ۭ Quranic annotation signs
  if (rune >= 0x200B && rune <= 0x200F) return true; // zero-width space .. right-to-left mark
  if (rune >= 0x202A && rune <= 0x202E) return true; // LRE .. RLO bidi embedding/override
  return false;
}
