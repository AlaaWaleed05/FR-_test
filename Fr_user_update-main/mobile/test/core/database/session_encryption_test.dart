import 'dart:io';

import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/database/session_encryption.dart';
import 'package:mobile/core/security/session_key_store.dart';
import 'package:sqlite3/sqlite3.dart' as raw_sqlite3;

import '../security/fake_session_key_store.dart';

/// Exercises `openEncryptedSessionExecutor` against REAL files on disk — never
/// `SessionDatabase.forTesting()`'s in-memory database, which would prove nothing about the
/// on-disk encryption this task exists to add. Uses `FakeSessionKeyStore` in place of
/// `flutter_secure_storage` (a platform channel unavailable under `flutter_test`) — the same
/// seam style as `FakeEntryApi`.
void main() {
  late Directory tempDir;
  late String path;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('fru_session_encryption_test_');
    path = '${tempDir.path}/session.sqlite';
  });

  tearDown(() => tempDir.deleteSync(recursive: true));

  test('first run: no key exists, no file exists — a key is generated, stored, and the '
      'database opens', () async {
    final keyStore = FakeSessionKeyStore();
    expect(await keyStore.readKey(), isNull);

    final db = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
    addTearDown(db.close);

    await db.into(db.localDraft).insert(
      LocalDraftCompanion.insert(
        id: const Value(0),
        accountNumber: const Value('0912345678'),
        updatedAt: DateTime.now(),
      ),
      mode: InsertMode.insertOrReplace,
    );

    expect(await keyStore.readKey(), isNotNull);
    expect(await keyStore.readKey(), hasLength(64));
    final row = await (db.select(db.localDraft)..where((t) => t.id.equals(0))).getSingle();
    expect(row.accountNumber, '0912345678');
  });

  test('second run: the same key is retrieved and the same data reads back', () async {
    final keyStore = FakeSessionKeyStore();

    final firstOpen = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
    await firstOpen.into(firstOpen.localDraft).insert(
      LocalDraftCompanion.insert(
        id: const Value(0),
        accountNumber: const Value('0912345678'),
        phoneNumber: const Value('+249912345678'),
        updatedAt: DateTime.now(),
      ),
      mode: InsertMode.insertOrReplace,
    );
    final keyAfterFirstRun = await keyStore.readKey();
    await firstOpen.close();

    // Simulates a fresh app process re-reading the SAME persisted key (still held by the same
    // FakeSessionKeyStore instance, standing in for the platform keystore surviving a restart).
    final secondOpen = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
    addTearDown(secondOpen.close);

    expect(await keyStore.readKey(), keyAfterFirstRun);
    final row = await (secondOpen.select(
      secondOpen.localDraft,
    )..where((t) => t.id.equals(0))).getSingle();
    expect(row.accountNumber, '0912345678');
    expect(row.phoneNumber, '+249912345678');
  });

  test('a pre-S5-03 plaintext file with no stored key is discarded, not migrated', () async {
    // Builds exactly what S5-01/S5-02 would have left on disk: a real, UNENCRYPTED sqlite3
    // file holding plaintext PII, and (matching production) no key ever written for it.
    final legacy = raw_sqlite3.sqlite3.open(path);
    legacy.execute('''
      CREATE TABLE local_draft (
        id INTEGER NOT NULL DEFAULT 0,
        account_number TEXT,
        phone_number TEXT,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (id)
      );
      INSERT INTO local_draft (id, account_number, phone_number, updated_at)
        VALUES (0, '0912345678', '+249912345678', 0);
    ''');
    legacy.close();
    expect(File(path).existsSync(), isTrue);

    final keyStore = FakeSessionKeyStore();
    final db = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
    addTearDown(db.close);

    // Fresh encrypted database — the old row is gone, not carried over.
    expect(await db.select(db.localDraft).get(), isEmpty);
    expect(await keyStore.readKey(), isNotNull);
  });

  test('the resulting file cannot be read as plaintext SQLite without the key, and can with it', () async {
    final keyStore = FakeSessionKeyStore();
    final db = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
    await db.into(db.localDraft).insert(
      LocalDraftCompanion.insert(
        id: const Value(0),
        accountNumber: const Value('0912345678'),
        updatedAt: DateTime.now(),
      ),
      mode: InsertMode.insertOrReplace,
    );
    await db.close();
    final key = await keyStore.readKey();

    final unkeyed = raw_sqlite3.sqlite3.open(path);
    addTearDown(unkeyed.close);
    expect(
      () => unkeyed.select('SELECT name FROM sqlite_master'),
      throwsA(
        isA<raw_sqlite3.SqliteException>().having(
          (e) => e.extendedResultCode,
          'extendedResultCode',
          26, // SQLITE_NOTADB
        ),
      ),
    );

    unkeyed.execute("PRAGMA key = '$key';");
    final tables = unkeyed
        .select("SELECT name FROM sqlite_master WHERE type = 'table'")
        .map((row) => row['name'] as String)
        .toList();
    expect(tables, contains('local_draft'));
  });

  // Regression tests for a BLOCKER found under review: flutter_secure_storage's Android backend
  // resets and returns the literal string "Data has been reset" from read() on a Keystore
  // decryption failure (its own `resetOnError` default) — without validation that string would
  // silently become the PRAGMA key for a brand-new database: a hardcoded-in-a-dependency key this
  // app never generated, and a plaintext file left un-discarded if one existed.
  test('a key store returning a value that is not a key this app could have generated is treated '
      'as no key — discards any existing plaintext file and generates a fresh valid key', () async {
    final legacy = raw_sqlite3.sqlite3.open(path);
    legacy.execute('''
      CREATE TABLE local_draft (
        id INTEGER NOT NULL DEFAULT 0, account_number TEXT, updated_at INTEGER NOT NULL,
        PRIMARY KEY (id)
      );
      INSERT INTO local_draft (id, account_number, updated_at) VALUES (0, '0912345678', 0);
    ''');
    legacy.close();

    final keyStore = _FixedValueKeyStore('Data has been reset');
    final db = SessionDatabase(await openEncryptedSessionExecutor(File(path), keyStore));
    addTearDown(db.close);

    expect(await db.select(db.localDraft).get(), isEmpty); // legacy row discarded, not migrated
    final storedKey = await keyStore.readKey();
    expect(storedKey, isNot('Data has been reset'));
    expect(storedKey, matches(RegExp(r'^[0-9a-f]{64}$')));
  });

  test('a key store whose readKey() throws is treated as no key, not propagated', () async {
    final legacy = raw_sqlite3.sqlite3.open(path);
    legacy.execute('''
      CREATE TABLE local_draft (
        id INTEGER NOT NULL DEFAULT 0, account_number TEXT, updated_at INTEGER NOT NULL,
        PRIMARY KEY (id)
      );
      INSERT INTO local_draft (id, account_number, updated_at) VALUES (0, '0912345678', 0);
    ''');
    legacy.close();

    final db = SessionDatabase(
      await openEncryptedSessionExecutor(File(path), _ThrowingReadKeyStore()),
    );
    addTearDown(db.close);

    expect(await db.select(db.localDraft).get(), isEmpty);
  });
}

class _FixedValueKeyStore implements SessionKeyStore {
  _FixedValueKeyStore(this._initial);
  final String? _initial;
  String? _written;

  @override
  Future<String?> readKey() async => _written ?? _initial;

  @override
  Future<void> writeKey(String key) async => _written = key;
}

class _ThrowingReadKeyStore implements SessionKeyStore {
  @override
  Future<String?> readKey() async => throw StateError('platform channel unavailable');

  @override
  Future<void> writeKey(String key) async {}
}
