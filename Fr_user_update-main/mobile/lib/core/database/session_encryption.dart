import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';

import '../security/session_key_store.dart';

/// Matches exactly `generateSessionEncryptionKey()`'s own output shape. Anything a
/// [SessionKeyStore] returns that does NOT match this is treated as "no key stored" (see
/// [openEncryptedSessionExecutor]) rather than used as an encryption key — found necessary under
/// review, S5-03: `flutter_secure_storage`'s Android backend resets and returns the literal
/// string `"Data has been reset"` from `read()` on a Keystore decryption failure
/// (`resetOnError`, its own default), which would otherwise silently become the `PRAGMA key`
/// value for a brand-new database — a hardcoded-in-a-dependency key this app never generated,
/// exactly the class of thing this task requires never happens.
final _validKeyPattern = RegExp(r'^[0-9a-f]{64}$');

/// Opens (creating if absent) a `sqlite3mc`-encrypted `SessionDatabase` file at [file], keyed
/// from [keyStore]. Deliberately pure (no Flutter import, no `path_provider`) so it can be driven
/// directly against real files — by `session_database_native.dart` on-device, by
/// `test/core/database/session_encryption_test.dart` against real temp files with a fake
/// `SessionKeyStore`, and by `bin/live_session_encryption_proof.dart` under plain `dart run`,
/// which cannot load anything depending on the Flutter engine.
///
/// **Discard, not migrate, a pre-S5-03 plaintext file** (deliberate choice, not a defect —
/// docs/sessions/2026-09-01-s5-03-local-encryption.md): treating "no usable key" (see below) as
/// the signal that [file] predates encryption works because this function always writes a key
/// before/at every successful open of a database it created. So "no usable key yet, but [file]
/// already exists" means either a device that ran a build before this one, or a [keyStore] read
/// this function could not trust — either way, that file (and any `-wal`/`-shm`/`-journal`
/// sidecars, whether or not [file] itself is still present) is deleted outright rather than
/// read, since reading it would mean handling its plaintext PII just to re-encrypt it for one
/// in-flight session the customer can re-enter in under a minute. A fresh key is generated
/// immediately after, so the caller always gets a real (if now empty) database.
///
/// A [keyStore] read that throws, or returns a value that is not a key this function could have
/// generated (see [_validKeyPattern]), is treated identically to "no key yet" — the safe
/// discard-and-regenerate path — never as a usable key and never as a reason to leave a plaintext
/// file in place.
Future<QueryExecutor> openEncryptedSessionExecutor(File file, SessionKeyStore keyStore) async {
  String? existingKey;
  try {
    existingKey = await keyStore.readKey();
  } catch (_) {
    existingKey = null;
  }
  if (existingKey != null && !_validKeyPattern.hasMatch(existingKey)) {
    existingKey = null;
  }

  if (existingKey == null) {
    if (await file.exists()) await file.delete();
    for (final suffix in ['-wal', '-shm', '-journal']) {
      final sidecar = File('${file.path}$suffix');
      if (await sidecar.exists()) await sidecar.delete();
    }
  }
  final key = existingKey ?? generateSessionEncryptionKey();
  if (existingKey == null) await keyStore.writeKey(key);

  return NativeDatabase.createInBackground(
    file,
    setup: (db) => db.execute("PRAGMA key = '$key';"),
  );
}
