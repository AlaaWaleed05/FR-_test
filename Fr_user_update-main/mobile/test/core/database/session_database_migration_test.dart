import 'dart:io';

// `show Value` only: drift's root export also carries `isNull`/`isNotNull` expression builders,
// which collide with the matchers of the same name.
import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:sqlite3/sqlite3.dart' as sqlite3;

/// Regression test for a BLOCKER found under review, S5-02: `LocalDraft`/`LocalProgress` were
/// added to `SessionDatabase` without bumping `schemaVersion` or declaring a `MigrationStrategy`.
/// `SessionDatabase.forTesting()` (used everywhere else in this suite) always opens a fresh
/// in-memory database, so `onCreate` always ran and no test could have caught a real device's
/// `session.sqlite` — created by an earlier build at `user_version = 1` — silently never gaining
/// the two new tables on `onCreate`, which drift only calls on a database's first-ever open.
void main() {
  test(
    'opening a real v1 file (PinnedReferenceVersions only, no LocalDraft/LocalProgress) '
    'upgrades in place and gains both new tables, without losing the existing data',
    () async {
      final tempDir = Directory.systemTemp.createTempSync('fru_session_migration_test_');
      addTearDown(() => tempDir.deleteSync(recursive: true));
      final path = '${tempDir.path}/session.sqlite';

      // Builds exactly what an S5-01 build would have left on disk: one table, no
      // LocalDraft/LocalProgress, `user_version = 1` — never through SessionDatabase itself,
      // which today only knows how to create the CURRENT schema.
      final v1 = sqlite3.sqlite3.open(path);
      v1.execute('''
        CREATE TABLE pinned_reference_versions (
          list_code TEXT NOT NULL,
          pinned_version INTEGER NOT NULL,
          pinned_at INTEGER NOT NULL,
          PRIMARY KEY (list_code)
        );
        INSERT INTO pinned_reference_versions (list_code, pinned_version, pinned_at)
          VALUES ('occupation', 3, 0);
        PRAGMA user_version = 1;
      ''');
      v1.close();

      final db = SessionDatabase(NativeDatabase(File(path)));
      addTearDown(db.close);

      // The bug: without the fix, these throw `SqliteException: no such table`.
      expect(await db.select(db.localDraft).get(), isEmpty);
      expect(await db.select(db.localProgress).get(), isEmpty);

      // The pre-existing table and its data survive the upgrade untouched.
      expect(await db.pinnedVersionFor('occupation'), 3);
    },
  );

  test(
    'opening a real v2 file (LocalDraft without emailSelected) upgrades in place and gains the '
    'new column, defaulted true, without losing existing rows',
    () async {
      final tempDir = Directory.systemTemp.createTempSync('fru_session_migration_test_');
      addTearDown(() => tempDir.deleteSync(recursive: true));
      final path = '${tempDir.path}/session.sqlite';

      final v2 = sqlite3.sqlite3.open(path);
      v2.execute('''
        CREATE TABLE pinned_reference_versions (
          list_code TEXT NOT NULL,
          pinned_version INTEGER NOT NULL,
          pinned_at INTEGER NOT NULL,
          PRIMARY KEY (list_code)
        );
        CREATE TABLE local_draft (
          id INTEGER NOT NULL DEFAULT 0,
          branch_code TEXT,
          account_number TEXT,
          phone_number TEXT,
          sms_selected INTEGER NOT NULL DEFAULT 1,
          whatsapp_selected INTEGER NOT NULL DEFAULT 1,
          email_address TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE local_progress (
          id INTEGER NOT NULL DEFAULT 0,
          verified_branch_code TEXT NOT NULL,
          verified_account_number TEXT NOT NULL,
          resume_stage TEXT NOT NULL,
          profile_id TEXT,
          channels_summary TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        INSERT INTO local_draft (id, account_number, updated_at) VALUES (0, '12345', 0);
        PRAGMA user_version = 2;
      ''');
      v2.close();

      final db = SessionDatabase(NativeDatabase(File(path)));
      addTearDown(db.close);

      final draft = await (db.select(db.localDraft)..where((t) => t.id.equals(0))).getSingle();
      expect(draft.accountNumber, '12345'); // pre-existing row survives
      expect(draft.emailSelected, isTrue); // new column, defaulted
    },
  );

  test(
    'opening a real v3 file (no DataEntryDraft/DataEntryIncomeSources/PendingStageSync) upgrades '
    'in place and gains all three new tables (S5-05)',
    () async {
      final tempDir = Directory.systemTemp.createTempSync('fru_session_migration_test_');
      addTearDown(() => tempDir.deleteSync(recursive: true));
      final path = '${tempDir.path}/session.sqlite';

      final v3 = sqlite3.sqlite3.open(path);
      v3.execute('''
        CREATE TABLE pinned_reference_versions (
          list_code TEXT NOT NULL,
          pinned_version INTEGER NOT NULL,
          pinned_at INTEGER NOT NULL,
          PRIMARY KEY (list_code)
        );
        CREATE TABLE local_draft (
          id INTEGER NOT NULL DEFAULT 0,
          branch_code TEXT,
          account_number TEXT,
          phone_number TEXT,
          sms_selected INTEGER NOT NULL DEFAULT 1,
          whatsapp_selected INTEGER NOT NULL DEFAULT 1,
          email_address TEXT,
          email_selected INTEGER NOT NULL DEFAULT 1,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE local_progress (
          id INTEGER NOT NULL DEFAULT 0,
          verified_branch_code TEXT NOT NULL,
          verified_account_number TEXT NOT NULL,
          resume_stage TEXT NOT NULL,
          profile_id TEXT,
          channels_summary TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        INSERT INTO local_progress
          (id, verified_branch_code, verified_account_number, resume_stage, updated_at)
          VALUES (0, '16', '0000000001', 'stage3', 0);
        PRAGMA user_version = 3;
      ''');
      v3.close();

      final db = SessionDatabase(NativeDatabase(File(path)));
      addTearDown(db.close);

      // The bug this guards against: without the fix, these throw `SqliteException: no such
      // table` — the exact failure mode the v1/v2 tests above already proved for the prior schema
      // bump, now proven again for this one.
      expect(await db.select(db.dataEntryDraft).get(), isEmpty);
      expect(await db.select(db.dataEntryIncomeSources).get(), isEmpty);
      expect(await db.select(db.pendingStageSync).get(), isEmpty);

      // The pre-existing row survives the upgrade untouched.
      final progress = await (db.select(
        db.localProgress,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(progress.resumeStage, 'stage3');

      // The v3→v5 half of S5-07's mutual-exclusion trap: `createTable` above builds the CURRENT
      // definition, `identity_type` included, so this device must NOT also run the v4→v5
      // `addColumn` — if it did, the upgrade would throw `duplicate column name`. Writing the
      // column proves it exists exactly once.
      await db
          .into(db.dataEntryDraft)
          .insertOnConflictUpdate(
            DataEntryDraftCompanion.insert(
              id: const Value(0),
              identityType: const Value('passport'),
              updatedAt: DateTime.now(),
            ),
          );
      final draft = await (db.select(
        db.dataEntryDraft,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(draft.identityType, 'passport');
    },
  );

  test(
    'opening a real v4 file (DataEntryDraft without identityType) upgrades in place and gains the '
    'column (S5-07)',
    () async {
      final tempDir = Directory.systemTemp.createTempSync('fru_session_migration_test_');
      addTearDown(() => tempDir.deleteSync(recursive: true));
      final path = '${tempDir.path}/session.sqlite';

      final v4 = sqlite3.sqlite3.open(path);
      v4.execute('''
        CREATE TABLE pinned_reference_versions (
          list_code TEXT NOT NULL,
          pinned_version INTEGER NOT NULL,
          pinned_at INTEGER NOT NULL,
          PRIMARY KEY (list_code)
        );
        CREATE TABLE local_draft (
          id INTEGER NOT NULL DEFAULT 0,
          branch_code TEXT,
          account_number TEXT,
          phone_number TEXT,
          sms_selected INTEGER NOT NULL DEFAULT 1,
          whatsapp_selected INTEGER NOT NULL DEFAULT 1,
          email_address TEXT,
          email_selected INTEGER NOT NULL DEFAULT 1,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE local_progress (
          id INTEGER NOT NULL DEFAULT 0,
          verified_branch_code TEXT NOT NULL,
          verified_account_number TEXT NOT NULL,
          resume_stage TEXT NOT NULL,
          profile_id TEXT,
          channels_summary TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE data_entry_draft (
          id INTEGER NOT NULL DEFAULT 0,
          sex_declared TEXT,
          ethnicity TEXT,
          country_of_residence_code TEXT,
          marital_status TEXT,
          spouse_name TEXT,
          has_children INTEGER,
          children_count INTEGER,
          education_level INTEGER,
          birth_country_code TEXT,
          birth_state_code TEXT,
          birth_state_text TEXT,
          birth_city_text TEXT,
          occupation_code TEXT,
          monthly_expenses_sdg TEXT,
          home_country_code TEXT,
          home_state_code TEXT,
          home_state_text TEXT,
          home_locality_code TEXT,
          home_locality_text TEXT,
          home_city TEXT,
          home_area TEXT,
          home_street TEXT,
          home_block TEXT,
          home_house_number TEXT,
          work_employer TEXT,
          work_country_code TEXT,
          work_state_code TEXT,
          work_state_text TEXT,
          work_locality_code TEXT,
          work_locality_text TEXT,
          work_city TEXT,
          work_area TEXT,
          work_street TEXT,
          work_block TEXT,
          salary_certificate_path TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE data_entry_income_sources (
          code TEXT NOT NULL,
          is_primary INTEGER NOT NULL DEFAULT 0,
          other_text TEXT,
          PRIMARY KEY (code)
        );
        CREATE TABLE pending_stage_sync (
          stage TEXT NOT NULL,
          queued_at INTEGER NOT NULL,
          PRIMARY KEY (stage)
        );
        INSERT INTO data_entry_draft (id, work_employer, updated_at)
          VALUES (0, 'شركة الاتصالات', 0);
        PRAGMA user_version = 4;
      ''');
      v4.close();

      final db = SessionDatabase(NativeDatabase(File(path)));
      addTearDown(db.close);

      // Without the v4→v5 arm this throws `SqliteException: no such column: identity_type` — the
      // same failure mode the v1/v2/v3 tests above prove for their own schema bumps.
      await (db.update(db.dataEntryDraft)..where((t) => t.id.equals(0))).write(
        const DataEntryDraftCompanion(identityType: Value('national_id')),
      );

      // **v6 (S8-14, BL-105), and this is the assertion that guards the trap.** A v4 device needs
      // BOTH addColumns. Written as `else { if (from<5) ...; if (from<6) ...; }` it gets both;
      // written as the `else if` chain this file used to have, the v6 column is silently skipped
      // on exactly the v4 devices that need it and this write throws `no such column:
      // salary_certificate_uploaded_at`.
      await (db.update(db.dataEntryDraft)..where((t) => t.id.equals(0))).write(
        DataEntryDraftCompanion(
          salaryCertificateUploadedAt: Value(DateTime.fromMillisecondsSinceEpoch(1757000000000)),
        ),
      );

      final draft = await (db.select(
        db.dataEntryDraft,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(draft.identityType, 'national_id');
      expect(
        draft.salaryCertificateUploadedAt,
        DateTime.fromMillisecondsSinceEpoch(1757000000000),
      );

      // The pre-existing stage 3-6 data survives both column additions untouched.
      expect(draft.workEmployer, 'شركة الاتصالات');
    },
  );

  test(
    'opening a real v5 file (DataEntryDraft with identityType but no salaryCertificateUploadedAt) '
    'upgrades in place and gains the column (S8-14, BL-105)',
    () async {
      final tempDir = Directory.systemTemp.createTempSync('fru_session_migration_test_');
      addTearDown(() => tempDir.deleteSync(recursive: true));
      final path = '${tempDir.path}/session.sqlite';

      // The v5 shape: everything v4 had, plus identity_type, minus the v6 column. This is what a
      // handset carrying the S8-11 build has on disk.
      final v5 = sqlite3.sqlite3.open(path);
      v5.execute('''
        CREATE TABLE pinned_reference_versions (
          list_code TEXT NOT NULL,
          pinned_version INTEGER NOT NULL,
          pinned_at INTEGER NOT NULL,
          PRIMARY KEY (list_code)
        );
        CREATE TABLE local_draft (
          id INTEGER NOT NULL DEFAULT 0,
          branch_code TEXT,
          account_number TEXT,
          phone_number TEXT,
          sms_selected INTEGER NOT NULL DEFAULT 1,
          whatsapp_selected INTEGER NOT NULL DEFAULT 1,
          email_address TEXT,
          email_selected INTEGER NOT NULL DEFAULT 1,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE local_progress (
          id INTEGER NOT NULL DEFAULT 0,
          verified_branch_code TEXT NOT NULL,
          verified_account_number TEXT NOT NULL,
          resume_stage TEXT NOT NULL,
          profile_id TEXT,
          channels_summary TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE data_entry_draft (
          id INTEGER NOT NULL DEFAULT 0,
          sex_declared TEXT,
          ethnicity TEXT,
          country_of_residence_code TEXT,
          marital_status TEXT,
          spouse_name TEXT,
          has_children INTEGER,
          children_count INTEGER,
          education_level INTEGER,
          birth_country_code TEXT,
          birth_state_code TEXT,
          birth_state_text TEXT,
          birth_city_text TEXT,
          occupation_code TEXT,
          monthly_expenses_sdg TEXT,
          home_country_code TEXT,
          home_state_code TEXT,
          home_state_text TEXT,
          home_locality_code TEXT,
          home_locality_text TEXT,
          home_city TEXT,
          home_area TEXT,
          home_street TEXT,
          home_block TEXT,
          home_house_number TEXT,
          work_employer TEXT,
          work_country_code TEXT,
          work_state_code TEXT,
          work_state_text TEXT,
          work_locality_code TEXT,
          work_locality_text TEXT,
          work_city TEXT,
          work_area TEXT,
          work_street TEXT,
          work_block TEXT,
          salary_certificate_path TEXT,
          identity_type TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (id)
        );
        CREATE TABLE data_entry_income_sources (
          code TEXT NOT NULL,
          is_primary INTEGER NOT NULL DEFAULT 0,
          other_text TEXT,
          PRIMARY KEY (code)
        );
        CREATE TABLE pending_stage_sync (
          stage TEXT NOT NULL,
          queued_at INTEGER NOT NULL,
          PRIMARY KEY (stage)
        );
        INSERT INTO data_entry_draft (id, identity_type, salary_certificate_path, updated_at)
          VALUES (0, 'passport', '/data/salary_certificate.jpg', 0);
        PRAGMA user_version = 5;
      ''');
      v5.close();

      final db = SessionDatabase(NativeDatabase(File(path)));
      addTearDown(db.close);

      await (db.update(db.dataEntryDraft)..where((t) => t.id.equals(0))).write(
        DataEntryDraftCompanion(
          salaryCertificateUploadedAt: Value(DateTime.fromMillisecondsSinceEpoch(1757000000000)),
        ),
      );
      final draft = await (db.select(
        db.dataEntryDraft,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(
        draft.salaryCertificateUploadedAt,
        DateTime.fromMillisecondsSinceEpoch(1757000000000),
      );

      // A certificate picked before the upgrade keeps its path and, crucially, arrives UNSTAMPED
      // — so Stage 6 treats it as not yet attached and retries, rather than inheriting a
      // confirmation nothing ever earned (BL-105).
      expect(draft.salaryCertificatePath, '/data/salary_certificate.jpg');
      expect(draft.identityType, 'passport');
    },
  );
}
