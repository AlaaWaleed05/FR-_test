// Live proof for S5-03 (closes R-025): drives the REAL openEncryptedSessionExecutor against a
// real file on disk — never SessionDatabase.forTesting()'s in-memory database — to show the
// actual on-disk encryption, not just a green test. Run with:
//   fvm dart run bin/live_session_encryption_proof.dart
//
// Lives under bin/ so it sits outside `flutter test`'s default `test/` target and
// `tool/check_coverage.dart`'s scan. Uses an in-memory fake in place of `SecureSessionKeyStore`
// (flutter_secure_storage's real platform channel) because `SecureSessionKeyStore` transitively
// imports `package:flutter/services.dart` and cannot compile under plain `dart run` — the same
// constraint documented on `session_key_store.dart`. This script is a proof of the SQLite/
// PRAGMA-key mechanism and the discard-on-legacy-file logic in `session_encryption.dart`, not of
// flutter_secure_storage's own plumbing, which is a well-documented first-party plugin.

import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/database/session_encryption.dart';
import 'package:mobile/core/security/session_key_store.dart';
import 'package:sqlite3/sqlite3.dart' as raw_sqlite3;

class _InMemoryKeyStore implements SessionKeyStore {
  String? _key;

  @override
  Future<String?> readKey() async => _key;

  @override
  Future<void> writeKey(String key) async => _key = key;

  void clear() => _key = null;
}

const phoneNumber = '+249912345678';
const accountNumber = '0000000001';

/// Never print the full key — it is a real (if ephemeral, temp-file-scoped) database key, and
/// CLAUDE.md's live-proof output gets pasted verbatim into the session report.
String _redact(String? key) => key == null ? 'null' : '${key.substring(0, 8)}... (${key.length} chars)';

Future<void> main() async {
  final tempDir = Directory.systemTemp.createTempSync('fru_live_session_encryption_');
  final path = '${tempDir.path}/session.sqlite';
  final keyStore = _InMemoryKeyStore();
  var allOk = true;

  void check(String label, bool ok) {
    stdout.writeln('${ok ? 'OK  ' : 'FAIL'} $label');
    if (!ok) allOk = false;
  }

  stdout.writeln('== First run: no key, no file ==');
  var db = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
  await db.into(db.localDraft).insert(
    LocalDraftCompanion.insert(
      id: const Value(0),
      accountNumber: const Value(accountNumber),
      phoneNumber: const Value(phoneNumber),
      updatedAt: DateTime.now(),
    ),
    mode: InsertMode.insertOrReplace,
  );
  await db.close();
  final generatedKey = await keyStore.readKey();
  stdout.writeln('generated key: ${_redact(generatedKey)}');
  check('key generated and stored (64 hex chars)', RegExp(r'^[0-9a-f]{64}$').hasMatch(generatedKey ?? ''));

  stdout.writeln('\n== Raw sqlite3 handle, NO key ==');
  final unkeyed = raw_sqlite3.sqlite3.open(path);
  try {
    unkeyed.select('SELECT name FROM sqlite_master');
    check('unkeyed SELECT rejected', false);
  } on raw_sqlite3.SqliteException catch (e) {
    stdout.writeln('caught (expected): $e');
    check('unkeyed SELECT rejected (extendedResultCode=${e.extendedResultCode})', true);
  }
  unkeyed.close();

  stdout.writeln('\n== Raw sqlite3 handle, WITH key ==');
  final keyed = raw_sqlite3.sqlite3.open(path);
  keyed.execute("PRAGMA key = '$generatedKey';");
  final tables = keyed
      .select("SELECT name FROM sqlite_master WHERE type = 'table'")
      .map((row) => row['name'] as String)
      .toList();
  stdout.writeln('tables visible with key: $tables');
  check('tables listed with key', tables.contains('local_draft'));
  keyed.close();

  stdout.writeln('\n== Raw byte scan of session.sqlite for plaintext PII ==');
  final bytes = File(path).readAsBytesSync();
  final asLatin1 = latin1.decode(bytes, allowInvalid: true);
  final phoneFound = asLatin1.contains(phoneNumber);
  final accountFound = asLatin1.contains(accountNumber);
  stdout.writeln('phone number "$phoneNumber" found in raw bytes: $phoneFound');
  stdout.writeln('account number "$accountNumber" found in raw bytes: $accountFound');
  check('phone number absent from raw bytes', !phoneFound);
  check('account number absent from raw bytes', !accountFound);

  stdout.writeln('\n== Second run: same fake key store, same file ==');
  db = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
  final row = await (db.select(db.localDraft)..where((t) => t.id.equals(0))).getSingle();
  await db.close();
  stdout.writeln('key retrieved: ${_redact(await keyStore.readKey())}');
  stdout.writeln('row read back: accountNumber=${row.accountNumber} phoneNumber=${row.phoneNumber}');
  check('same key retrieved on second run', await keyStore.readKey() == generatedKey);
  check('same data reads back on second run', row.accountNumber == accountNumber && row.phoneNumber == phoneNumber);

  stdout.writeln('\n== Discard path: pre-S5-03 plaintext file, no stored key ==');
  await File(path).delete();
  final legacy = raw_sqlite3.sqlite3.open(path);
  legacy.execute('''
    CREATE TABLE local_draft (
      id INTEGER NOT NULL DEFAULT 0, account_number TEXT, phone_number TEXT,
      updated_at INTEGER NOT NULL, PRIMARY KEY (id)
    );
    INSERT INTO local_draft (id, account_number, phone_number, updated_at)
      VALUES (0, '$accountNumber', '$phoneNumber', 0);
  ''');
  legacy.close();
  keyStore.clear();
  stdout.writeln('legacy plaintext file written, key store cleared');

  db = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
  final rowsAfterDiscard = await db.select(db.localDraft).get();
  await db.close();
  stdout.writeln('rows present after discard-and-recreate: ${rowsAfterDiscard.length}');
  check('legacy row discarded, not migrated', rowsAfterDiscard.isEmpty);
  check('a fresh key was generated for the new file', await keyStore.readKey() != null && await keyStore.readKey() != generatedKey);

  await tempDir.delete(recursive: true);

  if (!allOk) {
    stderr.writeln('\nFAILED: one or more checks above did not pass.');
    exit(1);
  }
  stdout.writeln('\nAll session-encryption checks passed.');
}
