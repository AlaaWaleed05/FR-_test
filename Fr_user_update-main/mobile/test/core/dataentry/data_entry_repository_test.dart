import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/dataentry/data_entry_models.dart';
import 'package:mobile/core/dataentry/data_entry_repository.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart'
    show BackendUnreachableException, ProfileAlreadyCompleteException;
import 'package:mobile/core/reference/reference_repository.dart';

import '../reference/fake_reference_api.dart';
import 'fake_data_entry_api.dart';
import 'seed_data_entry_catalog.dart';

void main() {
  late FakeDataEntryApi api;
  late SessionDatabase sessionDb;
  late ReferenceDatabase referenceDb;
  late FakeReferenceApi referenceApi;
  late DataEntryRepository repository;

  Future<void> seedVerifiedProfile() {
    return sessionDb
        .into(sessionDb.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '16',
            verifiedAccountNumber: '0000000001',
            resumeStage: 'stage3',
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );
  }

  setUp(() async {
    api = FakeDataEntryApi();
    sessionDb = SessionDatabase.forTesting();
    referenceDb = ReferenceDatabase.forTesting();
    referenceApi = FakeReferenceApi();
    repository = DataEntryRepository(
      api: api,
      sessionDb: sessionDb,
      referenceRepository: ReferenceRepository(api: referenceApi, db: referenceDb),
    );
    await seedVerifiedProfile();
  });

  tearDown(() async {
    await sessionDb.close();
    await referenceDb.close();
  });

  Future<bool> submitMinimalStage3({String ethnicity = 'عربي'}) {
    return repository.submitStage3(
      sexDeclared: 'm',
      ethnicity: ethnicity,
      countryOfResidenceCode: 'SD',
      maritalStatus: 'single',
      educationLevel: 3,
      birthCountryCode: 'SD',
      birthStateCode: '31',
      birthCityText: 'الخرطوم',
    );
  }

  group('prepareCatalog', () {
    test('returns true and activates all five lists when the catalogue is fully seeded', () async {
      seedDataEntryCatalog(referenceApi);

      final ready = await repository.prepareCatalog();

      expect(ready, isTrue);
      expect(await repository.pinnedVersion('country'), 1);
      expect(await repository.pinnedVersion('admin_division'), 1);
      expect(await repository.pinnedVersion('occupation'), 1);
      expect(await repository.pinnedVersion('income_source'), 1);
      expect(await repository.pinnedVersion('education_level'), 1);
    });

    test('returns false when no list has ever been cached — nothing to block stage 3 with '
        '(client rule 5)', () async {
      final ready = await repository.prepareCatalog();
      expect(ready, isFalse);
    });
  });

  group('submitStage3', () {
    test('a successful call returns true and leaves no pending row', () async {
      final landed = await submitMinimalStage3();

      expect(landed, isTrue);
      expect(api.submitStage3CallCount, 1);
      expect(api.lastStage3Args!['profileId'], 'p1');
      expect(await (sessionDb.select(sessionDb.pendingStageSync)).get(), isEmpty);
    });

    test('a BackendUnreachableException queues the stage and returns false, without throwing', () async {
      api.stage3ErrorToThrow = const BackendUnreachableException('offline');

      final landed = await submitMinimalStage3();

      expect(landed, isFalse);
      final pending = await (sessionDb.select(sessionDb.pendingStageSync)).get();
      expect(pending.map((r) => r.stage), ['stage3']);
    });

    test('a genuine rejection propagates and does NOT queue the stage', () async {
      api.stage3ErrorToThrow = const DataEntryRejectedException();

      await expectLater(submitMinimalStage3(), throwsA(isA<DataEntryRejectedException>()));
      expect(await (sessionDb.select(sessionDb.pendingStageSync)).get(), isEmpty);
    });

    test(
      'two concurrent calls for the SAME stage never send overlapping requests — a background '
      'flush and the customer\'s own "Next" racing the same stage must serialise, not interleave '
      '(S5-05 review)',
      () async {
        final gate = Completer<void>();
        api.stage3Gate = gate.future;

        final first = submitMinimalStage3(ethnicity: 'A');
        // Let the first call actually start (and block on the gate) before starting the second.
        await Future<void>.delayed(Duration.zero);
        final second = submitMinimalStage3(ethnicity: 'B');
        await Future<void>.delayed(Duration.zero);

        // The second call must NOT have started yet — it is waiting for the first to finish.
        expect(api.stage3CallOrder, ['start:A']);

        gate.complete();
        await Future.wait([first, second]);

        expect(api.stage3CallOrder, ['start:A', 'finish:A', 'start:B', 'finish:B']);
      },
    );
  });

  group('flushPending', () {
    Future<void> saveFullDraft() async {
      await repository.saveDraftFields(
        const DataEntryDraftCompanion(
          sexDeclared: Value('m'),
          ethnicity: Value('عربي'),
          countryOfResidenceCode: Value('SD'),
          maritalStatus: Value('single'),
          educationLevel: Value(3),
          birthCountryCode: Value('SD'),
          birthStateCode: Value('31'),
          birthCityText: Value('الخرطوم'),
          occupationCode: Value('1'),
          monthlyExpensesSdg: Value('50000'),
        ),
      );
      await repository.saveIncomeSources([
        const IncomeSourceEntry(code: 'RATIB', primary: true),
      ]);
    }

    test('resubmits every queued stage, in stage order, once reachable', () async {
      api.stage3ErrorToThrow = const BackendUnreachableException('offline');
      api.stage4ErrorToThrow = const BackendUnreachableException('offline');
      await saveFullDraft();
      await submitMinimalStage3();
      await repository.submitStage4(
        occupationCode: '1',
        incomeSources: const [IncomeSourceEntry(code: 'RATIB', primary: true)],
        monthlyExpensesSdg: '50000',
      );
      expect(await (sessionDb.select(sessionDb.pendingStageSync)).get(), hasLength(2));

      api.stage3ErrorToThrow = null;
      api.stage4ErrorToThrow = null;
      final landedCount = await repository.flushPending();

      expect(landedCount, 2);
      expect(await (sessionDb.select(sessionDb.pendingStageSync)).get(), isEmpty);
      expect(api.submitStage3CallCount, 2); // original attempt + flush
      expect(api.submitStage4CallCount, 2);
    });

    test('stops at the first stage still unreachable, leaving later ones queued untouched', () async {
      api.stage3ErrorToThrow = const BackendUnreachableException('offline');
      api.stage4ErrorToThrow = const BackendUnreachableException('offline');
      await saveFullDraft();
      await submitMinimalStage3();
      await repository.submitStage4(
        occupationCode: '1',
        incomeSources: const [IncomeSourceEntry(code: 'RATIB', primary: true)],
        monthlyExpensesSdg: '50000',
      );

      // stage3 stays unreachable; stage4 would succeed if tried, but must not be, since the
      // sweep stops at the first still-queued failure.
      final landedCount = await repository.flushPending();

      expect(landedCount, 0);
      final pending = await (sessionDb.select(sessionDb.pendingStageSync)).get();
      expect(pending.map((r) => r.stage).toSet(), {'stage3', 'stage4'});
      expect(api.submitStage4CallCount, 1); // only the original attempt, never retried
    });

    test('does nothing, with no network call, when nothing is pending', () async {
      final landedCount = await repository.flushPending();
      expect(landedCount, 0);
      expect(api.submitStage3CallCount, 0);
    });

    test(
      'a genuine rejection on one queued stage during the sweep drops that stage and continues '
      'to the next, rather than crashing the sweep (S5-05 review)',
      () async {
        api.stage3ErrorToThrow = const BackendUnreachableException('offline');
        api.stage4ErrorToThrow = const BackendUnreachableException('offline');
        await saveFullDraft();
        await submitMinimalStage3();
        await repository.submitStage4(
          occupationCode: '1',
          incomeSources: const [IncomeSourceEntry(code: 'RATIB', primary: true)],
          monthlyExpensesSdg: '50000',
        );

        // stage3 has since become genuinely invalid (e.g. its pinned version fell off the
        // floor); stage4 is fine.
        api.stage3ErrorToThrow = const DataEntryRejectedException();
        api.stage4ErrorToThrow = null;

        final landedCount = await repository.flushPending();

        expect(landedCount, 1); // only stage4 actually landed
        final pending = await (sessionDb.select(sessionDb.pendingStageSync)).get();
        expect(pending, isEmpty); // stage3's row dropped, not left retrying forever
      },
    );

    test(
      'a terminal-profile rejection on one queued stage stops the whole sweep, since nothing '
      'else queued can land either (S5-05 review)',
      () async {
        api.stage3ErrorToThrow = const BackendUnreachableException('offline');
        api.stage4ErrorToThrow = const BackendUnreachableException('offline');
        await saveFullDraft();
        await submitMinimalStage3();
        await repository.submitStage4(
          occupationCode: '1',
          incomeSources: const [IncomeSourceEntry(code: 'RATIB', primary: true)],
          monthlyExpensesSdg: '50000',
        );

        api.stage3ErrorToThrow = const ProfileAlreadyCompleteException();
        api.stage4ErrorToThrow = null; // would succeed if tried — must not be

        final landedCount = await repository.flushPending();

        expect(landedCount, 0);
        expect(api.submitStage4CallCount, 1); // only the original attempt, never retried
      },
    );
  });

  group('saveDraftFields', () {
    test('a later save touching only stage 4 fields does not clobber stage 3 fields already saved',
        () async {
      await repository.saveDraftFields(
        const DataEntryDraftCompanion(ethnicity: Value('عربي'), sexDeclared: Value('m')),
      );
      await repository.saveDraftFields(
        const DataEntryDraftCompanion(occupationCode: Value('1')),
      );

      final draft = await repository.loadDraft();
      expect(draft!.ethnicity, 'عربي');
      expect(draft.sexDeclared, 'm');
      expect(draft.occupationCode, '1');
    });
  });

  group('saveIncomeSources / loadIncomeSources', () {
    test('a later save wholesale-replaces the previous selection', () async {
      await repository.saveIncomeSources([
        const IncomeSourceEntry(code: 'RATIB', primary: true),
        const IncomeSourceEntry(code: 'OTHER', primary: false, otherText: 'شيء آخر'),
      ]);
      await repository.saveIncomeSources([const IncomeSourceEntry(code: 'PENSION', primary: true)]);

      final sources = await repository.loadIncomeSources();
      expect(sources.map((s) => s.code), ['PENSION']);
      expect(sources.single.isPrimary, isTrue);
    });
  });

  group('BL-105 — the salary certificate actually reaches the bank', () {
    late Directory tempDir;

    setUp(() {
      tempDir = Directory.systemTemp.createTempSync('fru_salary_cert_test_');
      addTearDown(() => tempDir.deleteSync(recursive: true));
    });

    /// Writes the file AND records it on the draft, which is what Stage 6 does before uploading.
    /// The acceptance stamp is keyed to the draft's current path (G1), so a test that skips this
    /// is not modelling the real call.
    Future<File> writeCertificate(String name, List<int> bytes) async {
      final file = File('${tempDir.path}/$name')..writeAsBytesSync(bytes);
      await repository.saveDraftFields(
        DataEntryDraftCompanion(salaryCertificatePath: Value(file.path)),
      );
      return file;
    }

    test('uploads the file and stamps the draft, which is what earns «تم إرفاق»', () async {
      // Before S8-14 nothing in the app called this endpoint at all: the customer was shown the
      // confirmation the moment a file was picked, and the file was deleted with the rest of
      // local state when the session cleared. The stamp is the difference between a claim and a
      // fact.
      final file = await writeCertificate('salary_certificate.jpg', [1, 2, 3, 4]);

      final ok = await repository.uploadSalaryCertificate(file.path);

      expect(ok, isTrue);
      expect(api.uploadSalaryCertificateCallCount, 1);
      expect(api.lastSalaryCertificateArgs!['profileId'], 'p1');
      expect(api.lastSalaryCertificateArgs!['contentType'], 'image/jpeg');
      expect(api.lastSalaryCertificateArgs!['contentBase64'], base64Encode([1, 2, 3, 4]));

      final draft = await repository.loadDraft();
      expect(draft!.salaryCertificateUploadedAt, isNotNull);
    });

    test("a PDF is sent as application/pdf, matching the endpoint's allowed types", () async {
      final file = await writeCertificate('salary_certificate.pdf', [9, 9]);

      await repository.uploadSalaryCertificate(file.path);

      expect(api.lastSalaryCertificateArgs!['contentType'], 'application/pdf');
    });

    test(
      'an unreachable backend returns false and leaves the draft UNSTAMPED — never a false claim',
      () async {
        // The certificate gates nothing (customer.md Stage 6), so a failed upload must not throw
        // and must not block. The only thing it changes is what the screen may say.
        final file = await writeCertificate('salary_certificate.jpg', [1]);
        api.salaryCertificateErrorToThrow = const BackendUnreachableException('offline');

        final ok = await repository.uploadSalaryCertificate(file.path);

        expect(ok, isFalse);
        final draft = await repository.loadDraft();
        expect(draft?.salaryCertificateUploadedAt, isNull);
      },
    );

    test('a rejected upload also returns false rather than throwing into the journey', () async {
      final file = await writeCertificate('salary_certificate.jpg', [1]);
      api.salaryCertificateErrorToThrow = const ProfileAlreadyCompleteException();

      expect(await repository.uploadSalaryCertificate(file.path), isFalse);
      final draft = await repository.loadDraft();
      expect(draft?.salaryCertificateUploadedAt, isNull);
    });

    test(
      'a failing LOCAL WRITE returns false rather than throwing into the journey (F1)',
      () async {
        // `@agent-reviewer`, S8-14 first pass: the original version left `file.exists()` and the
        // draft write OUTSIDE the try. Stage 6 awaits this inside `_onNext`'s try, so an escape
        // here landed in the generic catch and stopped `advanceToStage`/`context.go('/stage-7')`
        // — the certificate BLOCKING completion, which customer.md Stage 6 forbids outright.
        //
        // **Closing the database up front does NOT test this** — `_requireProfileId()` reads the
        // same database and throws first, inside the try, so the write is never reached and the
        // assertion passes against the buggy code. My first attempt did exactly that and was
        // deleted. The fake's `onSalaryCertificateUpload` hook is what isolates it: everything
        // before the final `saveDraftFields` runs against a live database, and only that write
        // fails. Reverted, this test fails with the escaping exception.
        final file = await writeCertificate('salary_certificate.jpg', [7, 7, 7]);
        api.onSalaryCertificateUpload = () => unawaited(sessionDb.close());

        expect(await repository.uploadSalaryCertificate(file.path), isFalse);
      },
    );

    test(
      'a LATE success does not stamp a draft that now names a different file (G1)',
      () async {
        // `@agent-reviewer`, S8-14 second pass. The draft holds one path and one stamp, and the
        // stamp is not keyed to the file it belongs to — so a slow upload of A that completed
        // after the customer picked B was stamping a row naming B, and Stage 6 reads that stamp
        // back on re-entry. «تم إرفاق: B» for a file the bank never received: BL-105 restored
        // from disk, which the in-memory screen guard cannot reach.
        final fileA = await writeCertificate('salary_certificate.jpg', [1]);
        await repository.saveDraftFields(
          DataEntryDraftCompanion(salaryCertificatePath: Value('${tempDir.path}/other.pdf')),
        );

        final ok = await repository.uploadSalaryCertificate(fileA.path);

        expect(ok, isFalse);
        final draft = await repository.loadDraft();
        expect(draft?.salaryCertificateUploadedAt, isNull);
      },
    );

    test('a missing file is not sent at all', () async {
      expect(
        await repository.uploadSalaryCertificate('${tempDir.path}/does_not_exist.jpg'),
        isFalse,
      );
      expect(api.uploadSalaryCertificateCallCount, 0);
    });
  });

  group('BL-122 — Stage 6 tells the bank whether a certificate was attached at all', () {
    late Directory tempDir;

    setUp(() {
      tempDir = Directory.systemTemp.createTempSync('fru_salary_claim_test_');
      addTearDown(() => tempDir.deleteSync(recursive: true));
    });

    Future<bool> submitStage6() => repository.submitStage6(
      employer: 'Acme',
      countryCode: 'SD',
      stateCode: 'KH',
      localityCode: 'KH01',
      city: 'Khartoum',
      area: 'Area',
      street: 'Street',
      block: 'Block',
    );

    test('no file picked sends a false claim, which is how a decline is recorded', () async {
      await submitStage6();

      expect(api.lastStage6Args!['salaryCertificateAttached'], isFalse);
    });

    test('a picked file sends a true claim even though the upload never ran', () async {
      // The whole point of BL-122: this customer and the one above leave the SAME trace in
      // app.artifact_ref -- no row -- and the operator must still be able to tell them apart.
      // Nothing here uploads anything; picking the file is what makes the claim true.
      await repository.saveDraftFields(
        DataEntryDraftCompanion(
          salaryCertificatePath: Value('${tempDir.path}/salary_certificate.jpg'),
        ),
      );

      await submitStage6();

      expect(api.lastStage6Args!['salaryCertificateAttached'], isTrue);
    });

    test('the claim is true for a file whose upload failed, not just an unattempted one', () async {
      // A path with no acceptance stamp is precisely "chosen but never uploaded" -- the state
      // BL-122 says the mobile side already knew and threw away.
      final file = File('${tempDir.path}/salary_certificate.pdf')..writeAsBytesSync([1, 2, 3]);
      await repository.saveDraftFields(
        DataEntryDraftCompanion(salaryCertificatePath: Value(file.path)),
      );
      api.salaryCertificateErrorToThrow = Exception('backend unreachable');

      expect(await repository.uploadSalaryCertificate(file.path), isFalse);
      await submitStage6();

      final draft = await repository.loadDraft();
      expect(draft?.salaryCertificateUploadedAt, isNull);
      expect(api.lastStage6Args!['salaryCertificateAttached'], isTrue);
    });
  });
}
