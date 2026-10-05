import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/security/session_key_store.dart';

void main() {
  group('generateSessionEncryptionKey', () {
    test('produces a 64-character lowercase hex string (32 random bytes)', () {
      final key = generateSessionEncryptionKey();
      expect(key.length, 64);
      expect(RegExp(r'^[0-9a-f]{64}$').hasMatch(key), isTrue);
    });

    test('never repeats across calls', () {
      final keys = List.generate(20, (_) => generateSessionEncryptionKey());
      expect(keys.toSet().length, 20);
    });
  });
}
