import 'dart:async';

// `show Value` only: drift also exports `isNull`/`isNotNull` as SQL expression builders, which
// collide with the matchers of the same name.
import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/identityscan/identity_scan_models.dart';
import 'package:mobile/core/identityscan/identity_scan_repository.dart';
import 'package:mobile/core/identityscan/uqudo_scanner.dart';

import 'fake_identity_scan_api.dart';
import 'fake_uqudo_scanner.dart';

/// `IdentityScanRepository`'s two jobs: driving one scan attempt end to end, and owning the
/// retained-JWS lifecycle (BL-034). The second is the one worth testing hard — every clearing rule
/// is a decision about whether the customer rescans or not.
void main() {
  late SessionDatabase db;
  late FakeIdentityScanApi api;
  late FakeUqudoScanner scanner;
  late RetainedScanStore store;
  late IdentityScanRepository repository;

  setUp(() async {
    db = SessionDatabase.forTesting();
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '16',
            verifiedAccountNumber: '0000000001',
            resumeStage: 'stage8',
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );
    api = FakeIdentityScanApi();
    scanner = FakeUqudoScanner();
    store = RetainedScanStore();
    repository = IdentityScanRepository(
      api: api,
      scanner: scanner,
      sessionDb: db,
      retainedScans: store,
    );
  });

  tearDown(() => db.close());

  group('runScan', () {
    test('mints a token, launches the SDK with the BACKEND\'s session id and nonce, uploads', () async {
      final display = await repository.runScan(IdentityDocumentTypes.passport);

      expect(api.issueTokenCallCount, 1);
      expect(api.lastIssueTokenArgs!['documentType'], IdentityDocumentTypes.passport);

      // The sessionId/nonce handed to the SDK are the ones the backend issued and recorded
      // (V0040's pending_scan_session_id/pending_scan_nonce), never anything minted on the device
      // — the backend validates the returned JWS against its OWN record, not against what the
      // request claims.
      expect(scanner.enrollCallCount, 1);
      expect(scanner.lastEnrollArgs!['sessionId'], 'session-1');
      expect(scanner.lastEnrollArgs!['nonce'], 'nonce-1');
      expect(scanner.lastEnrollArgs!['accessToken'], 'test-access-token');

      expect(api.submitScanCallCount, 1);
      expect(api.lastSubmitScanArgs!['jws'], 'header.payload.signature');
      expect(display.nationalNumber, 'NID-TEST-0001');
    });

    test('an accepted scan leaves nothing retained', () async {
      await repository.runScan(IdentityDocumentTypes.passport);
      expect(repository.retainedScan, isNull);
    });

    test('a failed token call never launches the SDK and retains nothing', () async {
      // customer.md Stage 8: a token failure is a connectivity failure, not a scan failure, and is
      // not counted against the retry budget. Nothing was captured, so there is nothing to retain.
      api.issueTokenErrorToThrow = const BackendUnreachableException('offline');

      await expectLater(
        repository.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<BackendUnreachableException>()),
      );
      expect(scanner.enrollCallCount, 0);
      expect(repository.retainedScan, isNull);
    });

    test('an SDK failure retains nothing and never reaches the upload', () async {
      scanner.errorToThrow = const UqudoScanFailure('USER_CANCEL');

      await expectLater(
        repository.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<UqudoScanFailure>()),
      );
      expect(api.submitScanCallCount, 0);
      expect(repository.retainedScan, isNull);
    });

    test('an SDK that never answers times out instead of hanging forever', () async {
      // F-1. The pinned plugin resolves its `pendingResult` neither way on a null-Intent
      // RESULT_CANCELED, so the future below never settles on its own. Short timeout injected only
      // so this test does not wait three real minutes; the screen test drives the shipped duration.
      scanner.pendingEnroll = Completer<String>();
      final fastTimeout = IdentityScanRepository(
        api: api,
        scanner: scanner,
        sessionDb: db,
        retainedScans: store,
        scanTimeout: const Duration(milliseconds: 50),
      );

      await expectLater(
        fastTimeout.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<UqudoScanNoResponse>()),
      );
      expect(api.submitScanCallCount, 0);
      expect(fastTimeout.retainedScan, isNull);
    });

    test('a JWS arriving AFTER the timeout is dropped, never retained and never uploaded', () async {
      // This is why the timeout wraps the enrol await alone. `Future.timeout` does not cancel what
      // it wraps, so the late answer genuinely does arrive — what must be true is that it reaches
      // nothing. Wrapped any further out it would still `keep()` into the process-scoped store and
      // still fire an upload, against an attempt the screen has by then already cancelled, leaving
      // Stage 8 offering to re-send a capture the backend was told was abandoned.
      final pending = Completer<String>();
      scanner.pendingEnroll = pending;
      final fastTimeout = IdentityScanRepository(
        api: api,
        scanner: scanner,
        sessionDb: db,
        retainedScans: store,
        scanTimeout: const Duration(milliseconds: 50),
      );

      await expectLater(
        fastTimeout.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<UqudoScanNoResponse>()),
      );

      // The SDK finally answers, long after the app gave up on it.
      pending.complete('late.payload.signature');
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(fastTimeout.retainedScan, isNull);
      expect(api.submitScanCallCount, 0);
    });

    test('the shipped no-answer timeout is the 180s F-1 settled on', () {
      // A reasoned figure, not a magic number — see `uqudoScanNoAnswerTimeout`'s own doc comment
      // for why it errs long. Pinned here so changing it is a deliberate act, not a drift.
      expect(uqudoScanNoAnswerTimeout, const Duration(minutes: 3));
    });
  });

  group('BL-034 — a lost acknowledgement retries the same JWS, never a rescan', () {
    test('a dropped upload retains the capture', () async {
      api.submitScanErrorToThrowOnce = const BackendUnreachableException('connection reset');

      await expectLater(
        repository.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<BackendUnreachableException>()),
      );

      final retained = repository.retainedScan;
      expect(retained, isNotNull);
      expect(retained!.jws, 'header.payload.signature');
      expect(retained.sessionId, 'session-1');
      expect(retained.nonce, 'nonce-1');
      expect(retained.documentType, IdentityDocumentTypes.passport);
    });

    test('the retry posts a BYTE-IDENTICAL submission and does not re-invoke the SDK', () async {
      api.submitScanErrorToThrowOnce = const BackendUnreachableException('connection reset');
      await expectLater(
        repository.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<BackendUnreachableException>()),
      );

      final display = await repository.retryUpload();

      // Identical is the whole contract, not merely "a second call happened": the backend
      // re-checks sessionId/nonce against its own record BEFORE the duplicate check, and the
      // duplicate check itself matches the JWS's jti. Any changed field is judged a fresh
      // submission and spends an attempt.
      expect(api.submitScanCalls, hasLength(2));
      expect(api.submitScanCalls[1], equals(api.submitScanCalls[0]));

      // The customer did not rescan — that is the point of the whole mechanism.
      expect(scanner.enrollCallCount, 1);
      expect(display.nationalNumber, 'NID-TEST-0001');
    });

    test('a successful retry clears the capture', () async {
      api.submitScanErrorToThrowOnce = const BackendUnreachableException('connection reset');
      await expectLater(
        repository.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<BackendUnreachableException>()),
      );
      await repository.retryUpload();
      expect(repository.retainedScan, isNull);
    });

    test('retryUpload with nothing retained is a caller bug, not a silent no-op', () async {
      expect(() => repository.retryUpload(), throwsA(isA<StateError>()));
    });
  });

  group('the JWS never reaches durable storage', () {
    test('a retained capture leaves no trace of the JWS anywhere in session.db', () async {
      // This is the one guard in the slice a green suite could otherwise coexist with while it was
      // broken: nothing else in these tests would fail if `keep()` also wrote the JWS to drift.
      // Retention is in-memory ONLY by product-owner decision — durable cross-restart retention is
      // deferred with its own retention rule, and a JWS is a PII-bearing identity artifact, so a
      // copy in `session.sqlite` would be exactly the leak that decision exists to prevent.
      // Asserted against the database's own contents rather than by reading the code.
      const jws = 'a-very-distinctive-jws-value-that-must-not-be-persisted';
      scanner.jws = jws;
      api.submitScanErrorToThrowOnce = const BackendUnreachableException('connection reset');

      await expectLater(
        repository.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<BackendUnreachableException>()),
      );
      // It IS retained in memory — otherwise this test would pass trivially against a version that
      // simply lost the capture.
      expect(repository.retainedScan!.jws, jws);

      // Every row of every table the session database holds, rendered as text.
      final dump = <String>[
        for (final row in await db.select(db.dataEntryDraft).get()) row.toString(),
        for (final row in await db.select(db.localProgress).get()) row.toString(),
        for (final row in await db.select(db.localDraft).get()) row.toString(),
        for (final row in await db.select(db.dataEntryIncomeSources).get()) row.toString(),
        for (final row in await db.select(db.pendingStageSync).get()) row.toString(),
        for (final row in await db.select(db.pinnedReferenceVersions).get()) row.toString(),
      ].join('\n');

      expect(dump.contains(jws), isFalse, reason: 'the JWS must never be persisted');
      // The sessionId and nonce are equally session-scoped and equally have no business on disk.
      expect(dump.contains('session-1'), isFalse);
      expect(dump.contains('nonce-1'), isFalse);
      // Nor may the Uqudo access token, which is a tenant-scoped bearer credential.
      expect(dump.contains('test-access-token'), isFalse);
    });
  });

  group('which outcomes end a capture and which leave it retryable', () {
    test('SCAN_REJECTED clears it — the same bytes would fail identically and spend again', () async {
      api.submitScanErrorToThrow = const ScanRejectedException();
      await expectLater(
        repository.runScan(IdentityDocumentTypes.passport),
        throwsA(isA<ScanRejectedException>()),
      );
      expect(repository.retainedScan, isNull);
    });

    // customer.md Stage 13: "The app must distinguish retrying an upload from this capture can no
    // longer be used". These two codes are that second case — Uqudo deletes the session images
    // when the JWS expires, and a JWS that still verifies is still unusable without them.
    for (final code in [ScanConflictCode.artifactExpired, ScanConflictCode.imagesUnavailable]) {
      test('${code.wire} clears it — the capture can no longer be used', () async {
        api.submitScanErrorToThrow = ScanConflictException(code);
        await expectLater(
          repository.runScan(IdentityDocumentTypes.passport),
          throwsA(isA<ScanConflictException>()),
        );
        expect(repository.retainedScan, isNull);
      });
    }

    for (final code in [
      ScanConflictCode.registryPending,
      ScanConflictCode.stateConflict,
      ScanConflictCode.profileTerminal,
    ]) {
      test('${code.wire} leaves it retained — nothing about the capture went wrong', () async {
        api.submitScanErrorToThrow = ScanConflictException(code);
        await expectLater(
          repository.runScan(IdentityDocumentTypes.passport),
          throwsA(isA<ScanConflictException>()),
        );
        expect(repository.retainedScan, isNotNull);
      });
    }
  });

  group('cancel and Stage 9 pass-throughs', () {
    test('cancelScan is what spends the attempt, and only when called', () async {
      await repository.cancelScan(IdentityDocumentTypes.nationalId);
      expect(api.cancelScanCallCount, 1);
      expect(api.lastCancelScanArgs!['documentType'], IdentityDocumentTypes.nationalId);
      expect(api.lastCancelScanArgs!['profileId'], 'p1');
    });

    test('currentReview reads without triggering a lookup', () async {
      await repository.currentReview();
      expect(api.currentReviewCallCount, 1);
      // The read-only endpoint, never the writing one — this is the whole reason S5-11 exists.
      expect(api.retryRegistryLookupCallCount, 0);
    });

    test('reportWrongNumber surfaces the block deadline when the budget was exhausted', () async {
      api.wrongNumberBlockedUntil = DateTime.utc(2026, 9, 6, 10, 15, 30);
      expect(await repository.reportWrongNumber(), DateTime.utc(2026, 9, 6, 10, 15, 30));
    });

    test('advanceToStage moves the device-owned resume pointer', () async {
      await repository.advanceToStage('stage9');
      final progress = await (db.select(
        db.localProgress,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(progress.resumeStage, 'stage9');
    });
  });
}
