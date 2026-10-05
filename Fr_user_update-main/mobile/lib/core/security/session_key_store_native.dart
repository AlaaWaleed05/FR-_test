import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'session_key_store.dart';

/// The real implementation, isolated in its own file for the same reason
/// `session_database_native.dart`/`reference_database_native.dart` isolate `path_provider`:
/// `flutter_secure_storage` imports `package:flutter/services.dart` and cannot compile under
/// plain `dart run` (used by `bin/live_session_encryption_proof.dart` and by anything importing
/// `session_key_store.dart`'s pure interface).
///
/// On Android: `flutter_secure_storage` 11's default `AndroidOptions()` — RSA-OAEP key wrapping
/// + AES-GCM data encryption, both Android-Keystore-backed. (The `encryptedSharedPreferences`/
/// Jetpack-Security path this project's AD-005 research cited was removed outright in 11.0.0,
/// replaced by this stronger default — [CHANGELOG.md, juliansteenbakker/flutter_secure_storage].)
/// On iOS: Keychain (`kSecClassGenericPassword`) — verified at S5-03 to carry no OpenSSL/
/// third-party crypto pod (`flutter_secure_storage_darwin`'s podspec depends only on `Flutter`).
class SecureSessionKeyStore implements SessionKeyStore {
  const SecureSessionKeyStore([this._storage = const FlutterSecureStorage()]);

  final FlutterSecureStorage _storage;

  static const _storageKey = 'session_db_encryption_key';

  @override
  Future<String?> readKey() => _storage.read(key: _storageKey);

  @override
  Future<void> writeKey(String key) => _storage.write(key: _storageKey, value: key);
}
