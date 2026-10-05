import 'package:mobile/core/security/session_key_store.dart';

/// In-memory stand-in for the platform keystore/Keychain — no platform channel involved. A single
/// instance reused across two calls to `openEncryptedSessionExecutor` simulates a key surviving
/// between separate app runs; a fresh instance simulates a device with no key yet.
class FakeSessionKeyStore implements SessionKeyStore {
  String? _key;

  @override
  Future<String?> readKey() async => _key;

  @override
  Future<void> writeKey(String key) async => _key = key;
}
