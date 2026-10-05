import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/text/arabic_digit_input_formatter.dart';

void main() {
  group('ArabicDigitInputFormatter.transliterate', () {
    test('converts Arabic-Indic digits (٠-٩) to ASCII', () {
      expect(ArabicDigitInputFormatter.transliterate('٠١٢٣٤٥٦٧٨٩'), '0123456789');
    });

    test('converts Extended Arabic-Indic digits (۰-۹) to ASCII', () {
      expect(ArabicDigitInputFormatter.transliterate('۰۱۲۳۴۵۶۷۸۹'), '0123456789');
    });

    test('leaves ASCII digits and other characters untouched', () {
      expect(ArabicDigitInputFormatter.transliterate('+249912345678'), '+249912345678');
    });

    test('converts a mixed real-world phone number', () {
      expect(ArabicDigitInputFormatter.transliterate('٠٩١٢٣٤٥٦٧٨'), '0912345678');
    });
  });

  group('as a TextInputFormatter', () {
    const formatter = ArabicDigitInputFormatter();

    test('formatEditUpdate converts and collapses the cursor to the end', () {
      final result = formatter.formatEditUpdate(
        TextEditingValue.empty,
        const TextEditingValue(text: '٠٩١', selection: TextSelection.collapsed(offset: 3)),
      );
      expect(result.text, '091');
      expect(result.selection, const TextSelection.collapsed(offset: 3));
    });

    test('returns the value unchanged when there is nothing to convert', () {
      const value = TextEditingValue(text: '091', selection: TextSelection.collapsed(offset: 3));
      final result = formatter.formatEditUpdate(TextEditingValue.empty, value);
      expect(identical(result, value), isTrue);
    });
  });
}
