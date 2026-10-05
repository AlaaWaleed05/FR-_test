import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/reference/retry_backoff.dart';

void main() {
  group('backoffFor', () {
    test('no prior failures means no wait', () {
      expect(backoffFor(0), Duration.zero);
    });

    test('backoff grows with each prior failure', () {
      final first = backoffFor(1);
      final second = backoffFor(2);
      expect(second, greaterThan(first));
    });

    test('backoff is capped, not unbounded', () {
      final capped = backoffFor(20);
      expect(capped, lessThanOrEqualTo(const Duration(minutes: 5)));
    });

    test('the cap holds exactly at very large counts — no 64-bit shift overflow', () {
      // A raw `1 << (n-1)` shift overflows around n=61 and wraps to a small/negative value,
      // silently defeating the cap (found under review, 2026-09-01). `totalFailureCount` is
      // genuinely unbounded for a persistently-failing fetch that never hash-mismatches.
      expect(backoffFor(100), const Duration(seconds: 120));
      expect(backoffFor(1 << 40), const Duration(seconds: 120));
    });
  });

  group('isPoisonedAfter', () {
    test('not poisoned after a single failure', () {
      expect(isPoisonedAfter(1), isFalse);
    });

    test('poisoned at the threshold', () {
      expect(isPoisonedAfter(poisonThreshold), isTrue);
    });

    test('stays poisoned beyond the threshold', () {
      expect(isPoisonedAfter(poisonThreshold + 5), isTrue);
    });
  });
}
