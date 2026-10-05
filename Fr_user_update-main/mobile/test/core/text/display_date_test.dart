import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/text/display_date.dart';

void main() {
  group('formatIsoDate — D4.2, DD/MM/YYYY and never ISO on a customer screen', () {
    test('renders an ISO date as DD/MM/YYYY with Latin digits and slash separators', () {
      expect(formatIsoDate('1990-04-17'), '17/04/1990');
      expect(formatIsoDate('2026-12-01'), '01/12/2026');
    });

    test('keeps the leading zeros rather than trimming them', () {
      // A registry date is compared against a printed document, so a stable two-digit shape
      // reads faster than a ragged one.
      expect(formatIsoDate('2001-01-09'), '09/01/2001');
    });

    test('takes the date half of an ISO datetime', () {
      expect(formatIsoDate('2026-09-07T14:32:10Z'), '07/09/2026');
      expect(formatIsoDate('2026-09-07 14:32:10'), '07/09/2026');
    });

    test('null stays null and blank comes back byte-identical, not trimmed', () {
      expect(formatIsoDate(null), isNull);
      expect(formatIsoDate(''), '');
      expect(formatIsoDate('   '), '   ');
    });

    test('surrounding whitespace does not stop a real date being recognised', () {
      expect(formatIsoDate(' 1990-04-17 '), '17/04/1990');
    });

    test('an unparseable value is returned UNCHANGED, never blanked', () {
      // This is the customer's own registry data. If the backend ever sends a shape this does
      // not recognise, showing it as it came is right — silently blanking it would hide a real
      // backend change behind a formatter, and the customer would be asked to verify nothing.
      expect(formatIsoDate('17/04/1990'), '17/04/1990');
      expect(formatIsoDate('not a date'), 'not a date');
      expect(formatIsoDate('1990-4-7'), '1990-4-7');
    });

    test('does not reformat a value that merely contains a date', () {
      expect(formatIsoDate('born 1990-04-17'), 'born 1990-04-17');
    });
  });
}
