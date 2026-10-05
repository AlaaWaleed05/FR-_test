// Live two-tier proof for S5-02/S5-04: drives the REAL DioEntryApi/EntryRepository code (the
// exact classes ContactChannelsScreen/AccountEntryScreen/ChannelVerificationScreen use) against a
// running backend for Stage 1a's three outcomes, a Stage 1b submission, and Stage 2's
// wrong-code/lock/resend paths. Run with (FRU_APP_PASSWORD must be exported first — see below):
//   fvm dart run bin/live_entry_flow_proof.dart --base-url=http://localhost:8080
//
// Lives under bin/ so it sits outside `flutter test`'s default `test/` target and
// `tool/check_coverage.dart`'s scan — no gate configuration changes needed.
//
// Uses the two accounts seeded in backend/src/main/resources/application.properties
// (`fru.core-banking.stub.accounts`): account ...0001 (found), ...0002 (seeded to the
// middleware's -1 "System Error", which the backend answers with 503 -- S3-02).
// Any other account number is unseeded and therefore INVALID by the stub's own honest-default
// design (see StubCoreBankingClient).
//
// **S5-04 architectural constraint, not a proof gap**: `StubMessageSender`
// (backend/.../messaging/stub/StubMessageSender.java) deliberately never retains a sent OTP
// code anywhere reachable outside the same JVM — `app.otp_challenge` stores only a salted hash
// (see OtpVerificationIntegrationTest's own doc comment). This external, HTTP-only script
// therefore cannot obtain a real code and cannot drive a genuine VERIFIED outcome; every code
// used below is deliberately wrong, driving WRONG_CODE/CHANNEL_LOCKED/resend paths only. VERIFIED
// is proven instead by `entry_repository_test.dart`/`channel_verification_screen_test.dart`
// against `FakeEntryApi` — see the S5-04 session report.
//
// **No OTP code, real or synthetic, is ever printed by this script** — outcomes and channel/state
// names only, per CLAUDE.md's redaction discipline.
//
// The resend-cap proof needs `FRU_APP_PASSWORD` (from the repo-root `.env`, never printed) to
// backdate `app.otp_challenge.issued_at` via `docker exec`, bypassing the 30/60/120s real-time
// delay gate the same way `OtpVerificationIntegrationTest.backdateMostRecentIssuedAt` does at the
// SQL level. Run from `mobile/`:
//   set -a; source ../.env; set +a; fvm dart run bin/live_entry_flow_proof.dart

import 'dart:io';

import 'package:dio/dio.dart';
import 'package:mobile/core/entry/dio_entry_api.dart';
import 'package:mobile/core/entry/entry_models.dart';

Future<void> main(List<String> args) async {
  final baseUrlArg = args.firstWhere(
    (a) => a.startsWith('--base-url='),
    orElse: () => '--base-url=http://localhost:8080',
  );
  final baseUrl = baseUrlArg.substring('--base-url='.length);

  stdout.writeln('Driving Stage 1a/1b against $baseUrl ...');
  final api = DioEntryApi(Dio(BaseOptions(baseUrl: baseUrl)));

  var allOk = true;

  Future<void> checkAccount(String label, String accountNumber) async {
    final result = await api.checkAccount('16', accountNumber);
    stdout.writeln(
      '$label: outcome=${result.outcome.name} continuation=${result.continuation.name} '
      'requestId=${result.requestId}',
    );
  }

  await checkAccount('invalid account (0000009998)', '0000009998');
  // S3-02: ...0002 is seeded to -1, so the backend answers 503 and DioEntryApi maps every 5xx to
  // BackendUnreachableException -- the expected shape here, not a failure of the script.
  try {
    await checkAccount('system-error account (0000000002)', '0000000002');
    stdout.writeln('UNEXPECTED: the system-error account did not raise');
  } on BackendUnreachableException catch (e) {
    stdout.writeln('system-error account (0000000002): BackendUnreachableException (${e.message})');
  }
  await checkAccount('active account (0000000001)', '0000000001');

  stdout.writeln('Submitting Stage 1b for the active account ...');
  String? profileId;
  try {
    // Re-entry (S3-07): resubmitting for an account with an existing incomplete profile updates
    // it and issues fresh challenges with reset per-channel counters (R-044) — exactly what
    // Stage 2's proofs below need a clean slate for, on every run of this script.
    final result = await api.submitContactChannels(
      branch: '16',
      accountNumber: '0000000001',
      phoneNumber: '0912345678',
      sms: true,
      whatsapp: true,
      emailAddress: 'proof@example.com',
    );
    profileId = result.profileId;
    stdout.writeln('profileId=$profileId');
    for (final channel in result.channels) {
      stdout.writeln('  ${channel.channel}: ${channel.state.name} ${channel.maskedDestination}');
    }
  } on Exception catch (e) {
    stderr.writeln('FAILED: contact-channels submit threw: $e');
    allOk = false;
  }

  if (!allOk || profileId == null) exit(1);
  stdout.writeln('Stage 1a/1b driven end to end successfully.');

  stdout.writeln('\n--- Stage 2 proofs (S5-04) ---');
  await driveStage2(api, profileId);
}

/// Every code below is deliberately wrong (never printed) — see this file's header comment for
/// why a genuine VERIFIED outcome cannot be driven live from outside the backend JVM.
Future<void> driveStage2(DioEntryApi api, String profileId) async {
  var wrongCodeCounter = 0;
  String nextWrongCode() {
    wrongCodeCounter++;
    return '9000${wrongCodeCounter.toString().padLeft(2, '0')}';
  }

  Future<OtpVerifyResult> verify(String channel, String label) async {
    final result = await api.verifyChannel(
      profileId: profileId,
      channel: channel,
      code: nextWrongCode(),
    );
    stdout.writeln(
      '$label: channel=$channel outcome=${result.outcome.name} state=${result.state.name} '
      'sessionBlockedUntil=${result.sessionBlockedUntil}',
    );
    return result;
  }

  stdout.writeln('\nWrong code in each phone row fails only that row:');
  await verify('sms', 'sms attempt 1');
  await verify('whatsapp', 'whatsapp attempt 1');

  stdout.writeln('\nExhausting sms\'s 5-attempt budget:');
  for (var i = 2; i <= 5; i++) {
    await verify('sms', 'sms attempt $i');
  }
  stdout.writeln('sms attempt 6 (must be CHANNEL_LOCKED, not WRONG_CODE):');
  await verify('sms', 'sms attempt 6');

  stdout.writeln('\nExhausting whatsapp\'s 5-attempt budget too — locks both phone channels:');
  DateTime? sessionBlockedUntil;
  for (var i = 2; i <= 5; i++) {
    final result = await verify('whatsapp', 'whatsapp attempt $i');
    sessionBlockedUntil ??= result.sessionBlockedUntil;
  }
  stdout.writeln(
    sessionBlockedUntil != null
        ? 'Both phone channels locked — sessionBlockedUntil=$sessionBlockedUntil '
              '(mobile screen navigates to /terminal on this signal)'
        : 'FAILED: expected sessionBlockedUntil to be set once both phone channels locked',
  );

  stdout.writeln('\nResend TOO_SOON (immediate second resend on email):');
  final firstResend = await api.resendChannel(profileId: profileId, channel: 'email');
  stdout.writeln('  1st resend: outcome=${firstResend.outcome.name}');
  final secondResend = await api.resendChannel(profileId: profileId, channel: 'email');
  stdout.writeln(
    '  2nd resend (immediate): outcome=${secondResend.outcome.name} '
    'secondsUntilAllowed=${secondResend.secondsUntilAllowed}',
  );

  stdout.writeln(
    '\nResend cap (3/channel/session) via backdated issued_at — looping until CAP_EXHAUSTED is '
    'observed, rather than assuming which attempt number it lands on:',
  );
  for (var attempt = 1; attempt <= 6; attempt++) {
    final backdated = await backdateMostRecentChallenge(profileId, 'email');
    if (!backdated) {
      stdout.writeln('  real resend $attempt: SKIPPED — could not backdate (see stderr)');
      continue;
    }
    final result = await api.resendChannel(profileId: profileId, channel: 'email');
    stdout.writeln('  real resend $attempt: outcome=${result.outcome.name}');
    if (result.outcome == OtpResendOutcome.capExhausted) break;
  }
}

/// Mirrors `OtpVerificationIntegrationTest.backdateMostRecentIssuedAt` at the SQL level, executed
/// via `docker exec` instead of JDBC — selects the row to backdate by MAX(expires_at), never
/// MAX(issued_at), for the same reason that test documents (issued_at is exactly the column being
/// mutated). Requires `FRU_APP_PASSWORD` in the environment; never printed, and psql itself never
/// echoes it. Returns false (proof step skipped, not failed) if the credential isn't set, so this
/// script still runs everything else without a local `.env`.
Future<bool> backdateMostRecentChallenge(String profileId, String channel) async {
  final password = Platform.environment['FRU_APP_PASSWORD'];
  if (password == null || password.isEmpty) {
    stderr.writeln(
      '  FRU_APP_PASSWORD not set — export it from the repo-root .env to run this step '
      '(see this file\'s header comment).',
    );
    return false;
  }
  final sql =
      "UPDATE app.otp_challenge SET issued_at = now() - interval '5 minutes' "
      "WHERE profile_id = '$profileId' AND channel = '$channel' AND consumed_at IS NULL "
      "AND expires_at = (SELECT max(expires_at) FROM app.otp_challenge "
      "WHERE profile_id = '$profileId' AND channel = '$channel')";
  // `-e PGPASSWORD` (no `=value`) forwards this process's own environment value for the
  // container-side variable of the same name, rather than embedding the plaintext password as an
  // argv element — found under review, S5-04: argv is visible in the host process table (`ps`),
  // which the original `PGPASSWORD=$password` form exposed the password to.
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
    sql,
  ], environment: {'PGPASSWORD': password});
  if (result.exitCode != 0) {
    stderr.writeln('  backdate failed: ${result.stderr}');
    return false;
  }
  return true;
}
