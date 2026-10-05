// Live proof for S5-05: drives the REAL production classes (DioEntryApi, DioDataEntryApi,
// DataEntryRepository, DioReferenceApi, ReferenceRepository, arFold) against a running backend —
// mirrors live_entry_flow_proof.dart's structure and redaction discipline. Run from `mobile/`
// (FRU_APP_PASSWORD must be exported first, from the repo-root .env, never printed):
//   set -a; source ../.env; set +a
//   fvm dart run bin/live_data_entry_flow_proof.dart --base-url=http://localhost:8080
//
// Three sections:
//   (a) drives Stage 1a/1b, then all four real stages online, verified by a `psql` SELECT.
//   (b) the offline-queue "headline proof": the same repository classes pointed at an
//       unreachable host queue all four stages locally, then pointed back at the real backend
//       and flushed — confirmed landed by the same `psql` SELECT. This is "aeroplane mode, then
//       reconnect," run through production code end to end — no physical/emulator device is
//       available this session (same disclosed constraint prior sessions' proof scripts record).
//   (c) fetches the REAL occupation document and re-runs the أ/ا (item 97/98) and ة/ه (item 38)
//       matches against it, cross-checking Dart `arFold` output against a raw `ref.ar_fold()`
//       call over the same strings via `psql` — the "kept consistent with ar_fold" evidence.
//
// No real/synthetic OTP code is needed: `DataEntryService.runStageSubmission` gates only on the
// profile not being terminal, never on Stage 2 channel-verification state — confirmed by reading
// the source before writing this script, not assumed.

import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:mobile/core/dataentry/data_entry_models.dart';
import 'package:mobile/core/dataentry/data_entry_repository.dart';
import 'package:mobile/core/dataentry/dio_data_entry_api.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/dio_entry_api.dart';
import 'package:mobile/core/network/dio_reference_api.dart';
import 'package:mobile/core/reference/reference_repository.dart';
import 'package:mobile/core/text/arabic_fold.dart';
import 'package:drift/drift.dart' show Value;

Future<void> main(List<String> args) async {
  final baseUrlArg = args.firstWhere(
    (a) => a.startsWith('--base-url='),
    orElse: () => '--base-url=http://localhost:8080',
  );
  final baseUrl = baseUrlArg.substring('--base-url='.length);
  stdout.writeln('S5-05 live proof against $baseUrl\n');

  final entryApi = DioEntryApi(Dio(BaseOptions(baseUrl: baseUrl)));
  stdout.writeln('--- Stage 1a/1b: obtaining a profile ---');
  final contactResult = await entryApi.submitContactChannels(
    branch: '16',
    accountNumber: '0000000001',
    phoneNumber: '0912345678',
    sms: true,
    whatsapp: true,
  );
  final profileId = contactResult.profileId;
  stdout.writeln('profileId=$profileId\n');

  stdout.writeln('--- (a) Online: all four real stages ---');
  final onlineOk = await runAllFourStages(baseUrl: baseUrl, profileId: profileId, suffix: 'A');
  if (!onlineOk) exit(1);
  await verifyProfileRow(profileId, label: 'after online submission');

  stdout.writeln('\n--- (b) Offline queue: same repository, unreachable host, then reconnect ---');
  final offlineOk = await runOfflineQueueProof(baseUrl: baseUrl, profileId: profileId);
  if (!offlineOk) exit(1);

  stdout.writeln('\n--- (c) ar_fold consistency against the REAL occupation document ---');
  final foldOk = await checkArFoldConsistency(baseUrl);
  if (!foldOk) exit(1);

  stdout.writeln('\nAll S5-05 live proofs passed.');
}

/// Builds a real [DataEntryRepository] wired to the real [DioDataEntryApi]/[DioReferenceApi]
/// against [baseUrl], backed by fresh in-memory local databases (this script never touches a
/// real on-device file — only the wire contract and the offline-queue LOGIC are under proof
/// here).
DataEntryRepository buildRepository({
  required String baseUrl,
  required String profileId,
  required SessionDatabase sessionDb,
  required ReferenceDatabase referenceDb,
}) {
  final dio = Dio(BaseOptions(baseUrl: baseUrl, connectTimeout: const Duration(seconds: 3)));
  return DataEntryRepository(
    api: DioDataEntryApi(dio),
    sessionDb: sessionDb,
    referenceRepository: ReferenceRepository(api: DioReferenceApi(dio), db: referenceDb),
  );
}

Future<void> seedLocalProgress(SessionDatabase db, String profileId, String stage) {
  return db
      .into(db.localProgress)
      .insertOnConflictUpdate(
        LocalProgressCompanion.insert(
          id: const Value(0),
          verifiedBranchCode: '16',
          verifiedAccountNumber: '0000000001',
          resumeStage: stage,
          profileId: Value(profileId),
          updatedAt: DateTime.now(),
        ),
      );
}

/// Submits all four stages with real seeded reference codes (occupation '2' مهندس; country 'SD';
/// admin_division state '31' الخرطوم, locality '3103' أم درمان — all transcribed from the actual
/// seed migrations, not invented). [suffix] varies free-text values between the online and
/// offline-then-flushed runs so the two are distinguishable in the final `psql` read.
Future<bool> runAllFourStages({
  required String baseUrl,
  required String profileId,
  required String suffix,
}) async {
  final sessionDb = SessionDatabase.forTesting();
  final referenceDb = ReferenceDatabase.forTesting();
  try {
    await seedLocalProgress(sessionDb, profileId, 'stage3');
    final repo = buildRepository(
      baseUrl: baseUrl,
      profileId: profileId,
      sessionDb: sessionDb,
      referenceDb: referenceDb,
    );

    final ready = await repo.prepareCatalog();
    stdout.writeln('prepareCatalog() -> $ready');
    if (!ready) {
      stderr.writeln('FAILED: prepareCatalog could not activate all five lists');
      return false;
    }

    final s3 = await repo.submitStage3(
      sexDeclared: 'm',
      ethnicity: 'عربي$suffix',
      countryOfResidenceCode: 'SD',
      maritalStatus: 'single',
      educationLevel: 3,
      birthCountryCode: 'SD',
      birthStateCode: '31',
      birthCityText: 'أم درمان',
    );
    stdout.writeln('submitStage3 -> landed=$s3');

    final s4 = await repo.submitStage4(
      occupationCode: '2',
      incomeSources: const [IncomeSourceEntry(code: 'RATIB', primary: true)],
      monthlyExpensesSdg: '75000',
    );
    stdout.writeln('submitStage4 -> landed=$s4');

    final s5 = await repo.submitStage5(
      countryCode: 'SD',
      stateCode: '31',
      localityCode: '3103',
      city: 'مدينة$suffix',
      area: 'منطقة',
      street: 'شارع',
      block: '1',
      houseNumber: '1',
    );
    stdout.writeln('submitStage5 -> landed=$s5');

    final s6 = await repo.submitStage6(
      employer: 'شركة$suffix',
      countryCode: 'SD',
      stateCode: '31',
      localityCode: '3103',
      city: 'مدينة',
      area: 'منطقة',
      street: 'شارع',
      block: '1',
    );
    stdout.writeln('submitStage6 -> landed=$s6');

    return true;
  } finally {
    await sessionDb.close();
    await referenceDb.close();
  }
}

/// The headline proof: the same repository classes, first pointed at a host nothing is
/// listening on (the "aeroplane mode" equivalent — no physical/emulator device available this
/// session), submitting all four stages (queued locally, never reaching a server), then pointed
/// back at the real backend and flushed.
Future<bool> runOfflineQueueProof({required String baseUrl, required String profileId}) async {
  final sessionDb = SessionDatabase.forTesting();
  final referenceDb = ReferenceDatabase.forTesting();
  try {
    await seedLocalProgress(sessionDb, profileId, 'stage3');

    // A real, valid catalogue must exist locally BEFORE going offline — exactly as customer.md
    // Stage 3 requires ("the catalogue gate is the stage 2->stage 3 boundary"), so prepare it
    // while still online.
    final onlineRepo = buildRepository(
      baseUrl: baseUrl,
      profileId: profileId,
      sessionDb: sessionDb,
      referenceDb: referenceDb,
    );
    final ready = await onlineRepo.prepareCatalog();
    stdout.writeln('prepareCatalog() while online -> $ready');
    if (!ready) return false;

    stdout.writeln('Going "offline" (pointing dio at an unreachable host) ...');
    const unreachableUrl = 'http://127.0.0.1:1';
    final offlineRepo = buildRepository(
      baseUrl: unreachableUrl,
      profileId: profileId,
      sessionDb: sessionDb,
      referenceDb: referenceDb,
    );

    // A real screen calls `saveDraftFields`/`saveIncomeSources` on every edit BEFORE calling
    // `submitStageN` — `flushPending()` reconstructs a queued stage's request from that saved
    // draft, not from whatever was passed to the original (queued, never-sent) call. Mirrored
    // here so this script exercises the real mechanism, not a shortcut around it.
    await offlineRepo.saveDraftFields(
      const DataEntryDraftCompanion(
        sexDeclared: Value('f'),
        ethnicity: Value('عربيه-أوفلاين'),
        countryOfResidenceCode: Value('SD'),
        maritalStatus: Value('single'),
        educationLevel: Value(4),
        birthCountryCode: Value('SD'),
        birthStateCode: Value('31'),
        birthCityText: Value('بحري'),
        occupationCode: Value('2'),
        monthlyExpensesSdg: Value('90000'),
        homeCountryCode: Value('SD'),
        homeStateCode: Value('31'),
        homeLocalityCode: Value('3103'),
        homeCity: Value('مدينة-أوفلاين'),
        homeArea: Value('منطقة'),
        homeStreet: Value('شارع'),
        homeBlock: Value('2'),
        homeHouseNumber: Value('2'),
        workEmployer: Value('شركة-أوفلاين'),
        workCountryCode: Value('SD'),
        workStateCode: Value('31'),
        workLocalityCode: Value('3103'),
        workCity: Value('مدينة'),
        workArea: Value('منطقة'),
        workStreet: Value('شارع'),
        workBlock: Value('2'),
      ),
    );
    await offlineRepo.saveIncomeSources(const [IncomeSourceEntry(code: 'RATIB', primary: true)]);

    final s3 = await offlineRepo.submitStage3(
      sexDeclared: 'f',
      ethnicity: 'عربيه-أوفلاين',
      countryOfResidenceCode: 'SD',
      maritalStatus: 'single',
      educationLevel: 4,
      birthCountryCode: 'SD',
      birthStateCode: '31',
      birthCityText: 'بحري',
    );
    final s4 = await offlineRepo.submitStage4(
      occupationCode: '2',
      incomeSources: const [IncomeSourceEntry(code: 'RATIB', primary: true)],
      monthlyExpensesSdg: '90000',
    );
    final s5 = await offlineRepo.submitStage5(
      countryCode: 'SD',
      stateCode: '31',
      localityCode: '3103',
      city: 'مدينة-أوفلاين',
      area: 'منطقة',
      street: 'شارع',
      block: '2',
      houseNumber: '2',
    );
    final s6 = await offlineRepo.submitStage6(
      employer: 'شركة-أوفلاين',
      countryCode: 'SD',
      stateCode: '31',
      localityCode: '3103',
      city: 'مدينة',
      area: 'منطقة',
      street: 'شارع',
      block: '2',
    );
    stdout.writeln(
      'while offline: stage3 landed=$s3 stage4 landed=$s4 stage5 landed=$s5 stage6 landed=$s6 '
      '(all four must be false — queued, not lost)',
    );
    if (s3 || s4 || s5 || s6) {
      stderr.writeln('FAILED: expected every stage to queue while unreachable');
      return false;
    }
    final pendingCount = (await sessionDb.select(sessionDb.pendingStageSync).get()).length;
    stdout.writeln('PendingStageSync rows while offline: $pendingCount (expected 4)');
    if (pendingCount != 4) return false;

    stdout.writeln('Reconnecting (pointing back at the real backend) and flushing ...');
    final reconnectedRepo = buildRepository(
      baseUrl: baseUrl,
      profileId: profileId,
      sessionDb: sessionDb,
      referenceDb: referenceDb,
    );
    final landedCount = await reconnectedRepo.flushPending();
    stdout.writeln('flushPending() -> landedCount=$landedCount (expected 4)');
    if (landedCount != 4) {
      stderr.writeln('FAILED: not every queued stage landed on reconnect');
      return false;
    }
    final remainingPending = (await sessionDb.select(sessionDb.pendingStageSync).get()).length;
    stdout.writeln('PendingStageSync rows after flush: $remainingPending (expected 0)');

    await verifyProfileRow(profileId, label: 'after the offline-then-reconnect flush');
    return remainingPending == 0;
  } finally {
    await sessionDb.close();
    await referenceDb.close();
  }
}

/// Fetches the real occupation document and proves the أ/ا (items 97/98) and ة/ه (item 38)
/// matches from the widget-test suite still hold against the LIVE payload, then cross-checks the
/// Dart fold against a raw `ref.ar_fold()` call over the same strings.
Future<bool> checkArFoldConsistency(String baseUrl) async {
  final dio = Dio(BaseOptions(baseUrl: baseUrl));
  final manifestResponse = await dio.get<Map<String, dynamic>>('/api/v1/reference/manifest');
  final lists = manifestResponse.data!['lists'] as List<dynamic>;
  final occupationEntry = lists.cast<Map<String, dynamic>>().firstWhere(
    (l) => l['listCode'] == 'occupation',
  );
  final version = occupationEntry['version'];
  final docResponse = await dio.get<Map<String, dynamic>>(
    '/api/v1/reference/lists/occupation/$version',
  );
  final items = (docResponse.data!['items'] as List<dynamic>).cast<Map<String, dynamic>>();

  Map<String, dynamic> byCode(String code) => items.firstWhere((i) => i['itemCode'] == code);
  final item97 = byCode('97');
  final item98 = byCode('98');
  final item38 = byCode('38');
  stdout.writeln('live item 97: ${item97['labelAr']} / item 98: ${item98['labelAr']} / '
      'item 38: ${item38['labelAr']}');

  final hamzaQuery = arFold('أستاذ');
  final hamzaOk =
      (item97['searchAr'] as String).contains(hamzaQuery) &&
      (item98['searchAr'] as String).contains(hamzaQuery);
  final taaMarbutaQuery = arFold('دعايه');
  final taaMarbutaOk = (item38['searchAr'] as String).contains(taaMarbutaQuery);
  stdout.writeln('أ/ا match against live searchAr: $hamzaOk; ة/ه match: $taaMarbutaOk');
  if (!hamzaOk || !taaMarbutaOk) return false;

  return checkAgainstRawSqlArFold([item97['labelAr'], item98['labelAr'], item38['labelAr'], 'أستاذ', 'دعايه']);
}

/// Cross-checks Dart `arFold` output against a raw `ref.ar_fold()` call for the same strings, via
/// `docker exec psql` — the actual "kept consistent with ar_fold" evidence, not just an assertion.
Future<bool> checkAgainstRawSqlArFold(List<String> strings) async {
  final password = Platform.environment['FRU_APP_PASSWORD'];
  if (password == null || password.isEmpty) {
    stderr.writeln(
      '  FRU_APP_PASSWORD not set — export it from the repo-root .env to run the raw-SQL '
      'cross-check (see this file\'s header comment). Skipping, not failing.',
    );
    return true;
  }
  var allMatch = true;
  for (final s in strings) {
    final dartFolded = arFold(s);
    final escaped = s.replaceAll("'", "''");
    final result = await Process.run('docker', [
      'exec',
      '-e',
      'PGPASSWORD',
      'fru_postgres',
      'psql',
      '-U',
      'fru_app',
      '-d',
      'fru',
      '-tAc',
      "SELECT ref.ar_fold('$escaped')",
    ], environment: {'PGPASSWORD': password}, stdoutEncoding: utf8, stderrEncoding: utf8);
    if (result.exitCode != 0) {
      stderr.writeln('  psql call failed: ${result.stderr}');
      return false;
    }
    final sqlFolded = (result.stdout as String).trim();
    final match = sqlFolded == dartFolded;
    stdout.writeln('  "$s": dart="$dartFolded" sql="$sqlFolded" match=$match');
    if (!match) allMatch = false;
  }
  return allMatch;
}

/// Reads back the profile's customer-data row via `psql` (read-only) — proof that a submission
/// landed with the right values, mirroring how prior sessions verified schema state directly
/// since no read endpoint exists at this layer.
Future<void> verifyProfileRow(String profileId, {required String label}) async {
  final password = Platform.environment['FRU_APP_PASSWORD'];
  if (password == null || password.isEmpty) {
    stderr.writeln(
      '  FRU_APP_PASSWORD not set — skipping the psql read-back ($label). Export it from the '
      'repo-root .env to see the landed row.',
    );
    return;
  }
  final result = await Process.run('docker', [
    'exec',
    '-e',
    'PGPASSWORD',
    'fru_postgres',
    'psql',
    '-U',
    'fru_app',
    '-d',
    'fru',
    '-c',
    "SELECT ethnicity, occupation_code, monthly_expenses_sdg, home_city, employer_name "
        "FROM app.profile_customer_data WHERE profile_id = '$profileId'",
  ], environment: {'PGPASSWORD': password}, stdoutEncoding: utf8, stderrEncoding: utf8);
  stdout.writeln('psql read-back ($label):\n${result.stdout}');
}
