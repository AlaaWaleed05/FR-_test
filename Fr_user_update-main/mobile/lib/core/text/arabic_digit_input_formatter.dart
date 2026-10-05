import 'package:flutter/services.dart';

/// Transliterates Arabic-Indic (٠-٩, U+0660–U+0669) and Extended Arabic-Indic (۰-۹,
/// U+06F0–U+06F9) digits to ASCII 0-9 on every edit.
///
/// Required ahead of `FilteringTextInputFormatter.digitsOnly`, which filters on `[0-9]` and
/// silently discards anything else — a customer typing on an Arabic keyboard is the expected
/// case for account number (Stage 1a) and phone number (Stage 1b), not an edge case
/// (docs/components/mobile-packages.md, "RTL — the four LTR islands"). The backend's own
/// `PhoneNumberNormalizer` rejects non-ASCII digits outright rather than transliterating them
/// (BL-016), so this conversion must happen client-side before the value is ever sent.
class ArabicDigitInputFormatter extends TextInputFormatter {
  const ArabicDigitInputFormatter();

  static const _arabicIndicZero = 0x0660;
  static const _extendedArabicIndicZero = 0x06F0;

  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final converted = transliterate(newValue.text);
    if (converted == newValue.text) return newValue;
    return newValue.copyWith(
      text: converted,
      selection: TextSelection.collapsed(offset: converted.length),
    );
  }

  /// Pure transliteration, exposed separately so it is testable with no widget/formatter
  /// machinery involved.
  static String transliterate(String input) {
    final buffer = StringBuffer();
    for (final rune in input.runes) {
      if (rune >= _arabicIndicZero && rune <= _arabicIndicZero + 9) {
        buffer.write(rune - _arabicIndicZero);
      } else if (rune >= _extendedArabicIndicZero && rune <= _extendedArabicIndicZero + 9) {
        buffer.write(rune - _extendedArabicIndicZero);
      } else {
        buffer.writeCharCode(rune);
      }
    }
    return buffer.toString();
  }
}
