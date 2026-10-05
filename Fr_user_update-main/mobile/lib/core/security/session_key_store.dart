import 'dart:math';

/// Where `session.sqlite`'s `sqlite3mc` encryption key lives. Abstracted so
/// `openEncryptedSessionExecutor` (session_encryption.dart) can be exercised in tests, and in
/// `bin/live_session_encryption_proof.dart`, against a real file with no platform channel — the
/// same seam style as `FakeEntryApi`.
///
/// Deliberately pure (no Flutter import): the real implementation,
/// `SecureSessionKeyStore` (session_key_store_native.dart), depends on `flutter_secure_storage`,
/// which transitively imports `package:flutter/services.dart` and cannot compile under plain
/// `dart run` — the same reason `path_provider` is kept out of `reference_database.dart`/
/// `session_database.dart` themselves (see those files' doc comments).
abstract class SessionKeyStore {
  Future<String?> readKey();
  Future<void> writeKey(String key);
}

/// 32 cryptographically random bytes (`Random.secure()`), hex-encoded to a 64-character ASCII
/// string — never hardcoded, never derived from anything the app ships. Hex avoids any quoting
/// hazard in the `PRAGMA key = '...'` literal it is later interpolated into.
String generateSessionEncryptionKey() {
  final random = Random.secure();
  final bytes = List<int>.generate(32, (_) => random.nextInt(256));
  return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
}
