import 'dart:async';

import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/identityscan/identity_scan_models.dart';
import 'package:mobile/core/identityscan/identity_scan_providers.dart';
import 'package:mobile/core/identityscan/uqudo_scanner.dart';
import 'package:mobile/features/identityscan/stage8_screen.dart';

import '../../core/identityscan/fake_identity_scan_api.dart';
import '../../core/identityscan/fake_uqudo_scanner.dart';

/// Stage 8 — document scan. **This file is R-052's consumer proof**: every code the backend can
/// answer with drives the screen customer.md specifies for it, and no screen anywhere counts
/// attempts locally.
void main() {
  Future<
    ({
      FakeIdentityScanApi api,
      FakeUqudoScanner scanner,
      SessionDatabase db,
      String? Function() landedPath,
    })
  >
  pumpStage8(
    WidgetTester tester, {
    String? identityType = IdentityDocumentTypes.passport,
    RetainedScanStore? retainedScans,
    // Simulates a 3-button Android navigation bar (walk comment 9, 2026-09-10). Zero by default,
    // which is what the test binding supplies and what let the defect ship unnoticed.
    double bottomInset = 0,
  }) async {
    final db = SessionDatabase.forTesting();
    addTearDown(db.close);
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
    if (identityType != null) {
      await db
          .into(db.dataEntryDraft)
          .insertOnConflictUpdate(
            DataEntryDraftCompanion.insert(
              id: const Value(0),
              identityType: Value(identityType),
              updatedAt: DateTime.now(),
            ),
          );
    }

    final api = FakeIdentityScanApi();
    final scanner = FakeUqudoScanner();
    String? landedPath;

    GoRoute stub(String path) => GoRoute(
      path: path,
      builder: (context, state) {
        landedPath = path;
        return Scaffold(body: Text('landed $path'));
      },
    );

    final router = GoRouter(
      initialLocation: '/stage-8',
      routes: [
        GoRoute(path: '/stage-8', builder: (context, state) => const Stage8Screen()),
        stub('/'),
        stub('/stage-7'),
        stub('/stage-9'),
        GoRoute(
          path: '/terminal',
          builder: (context, state) {
            landedPath = '/terminal';
            return Scaffold(body: Text(state.extra as String));
          },
        ),
        GoRoute(
          path: '/ended',
          builder: (context, state) {
            landedPath = '/ended';
            return const Scaffold(body: Text('ended'));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(db),
          identityScanApiProvider.overrideWithValue(api),
          uqudoScannerProvider.overrideWithValue(scanner),
          if (retainedScans != null)
            retainedScanStoreProvider.overrideWithValue(retainedScans),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(
              padding: EdgeInsets.only(bottom: bottomInset),
            ),
            child: Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (api: api, scanner: scanner, db: db, landedPath: () => landedPath);
  }

  Future<void> startScan(WidgetTester tester) async {
    await tester.tap(find.widgetWithText(FilledButton, 'ابدأ المسح'));
    await tester.pumpAndSettle();
  }

  group('preparation', () {
    testWidgets('names the chosen document and heads the get-ready guidance', (tester) async {
      await pumpStage8(tester);
      expect(find.textContaining('جواز السفر'), findsWidgets);
      // The attempt-cost sentence this used to assert was removed at S8-09 by product-owner
      // decision (walk comment 8b, asked twice). Asserting its ABSENCE, not just dropping the
      // check, so a future session cannot quietly reinstate it — the consequence of losing it is
      // recorded on BL-090 and is the product owner's to reverse, not a screen author's.
      expect(find.textContaining('يُحتسب الخروج من الكاميرا'), findsNothing);
      expect(find.text('قبل أن تبدأ:'), findsOneWidget);
    });

    testWidgets('each instruction carries an icon, and the bullet glyphs are gone', (
      tester,
    ) async {
      // Walk comment 8a. The bullet said only "this is a list"; an icon says WHICH instruction at
      // a glance, which is worth something on a mixed-literacy audience's last screen before a
      // camera opens. Asserting the words are UNCHANGED alongside it, because 8a asked for icons
      // to be added — not for the guidance to be rewritten.
      await pumpStage8(tester);
      await tester.pumpAndSettle();

      expect(find.textContaining('اجلس في مكان جيد الإضاءة'), findsOneWidget);
      expect(find.textContaining('•'), findsNothing);
      expect(find.byIcon(Icons.wb_sunny_outlined), findsOneWidget);
      expect(find.byIcon(Icons.camera_alt_outlined), findsOneWidget);
    });

    testWidgets('a missing identity type sends the customer back to Stage 7 rather than scanning', (
      tester,
    ) async {
      // Reachable when the resume pointer runs ahead of the draft. There is nothing to scan
      // without a chosen type, and inventing a default would silently scan the wrong document.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester, identityType: null);

      expect(landedPath(), '/stage-7');
      expect(api.issueTokenCallCount, 0);
      expect(scanner.enrollCallCount, 0);
    });
  });

  group('a retained capture is only re-offered when it still belongs to what is on screen', () {
    // Found by @agent-reviewer against this diff. The store is process-scoped — that is what makes
    // the retry survive navigation — so it can outlive the thing it was captured for.

    testWidgets(
      'a capture of the OTHER document type is discarded, not re-sent under the new type',
      (tester) async {
        // Otherwise a customer who switched passport → national ID at Stage 7 submits
        // identityType: national_id and then re-sends the retained PASSPORT JWS, leaving the
        // profile's Stage 7 answer and the accepted cycle disagreeing with nothing to reconcile
        // them.
        final store = RetainedScanStore()
          ..keep(
            const RetainedScan(
              profileId: 'p1',
              sessionId: 's1',
              nonce: 'n1',
              documentType: IdentityDocumentTypes.passport,
              jws: 'stale.passport.jws',
            ),
          );
        await pumpStage8(
          tester,
          identityType: IdentityDocumentTypes.nationalId,
          retainedScans: store,
        );

        expect(find.widgetWithText(FilledButton, 'ابدأ المسح'), findsOneWidget);
        expect(find.text('تم مسح الوثيقة، ولم يكتمل الإرسال'), findsNothing);
        expect(store.retained, isNull);
      },
    );

    testWidgets("a capture from a DIFFERENT profile's session is discarded", (tester) async {
      // `EntryRepository.abandon()` knows nothing about this store, so an abandoned session's
      // capture survives into the next one carrying the old profileId.
      final store = RetainedScanStore()
        ..keep(
          const RetainedScan(
            profileId: 'an-abandoned-session',
            sessionId: 's1',
            nonce: 'n1',
            documentType: IdentityDocumentTypes.passport,
            jws: 'stale.jws',
          ),
        );
      await pumpStage8(tester, retainedScans: store);

      expect(find.widgetWithText(FilledButton, 'ابدأ المسح'), findsOneWidget);
      expect(store.retained, isNull);
    });

    testWidgets('a matching capture IS re-offered', (tester) async {
      final store = RetainedScanStore()
        ..keep(
          const RetainedScan(
            profileId: 'p1',
            sessionId: 's1',
            nonce: 'n1',
            documentType: IdentityDocumentTypes.passport,
            jws: 'good.jws',
          ),
        );
      await pumpStage8(tester, retainedScans: store);

      expect(find.text('تم مسح الوثيقة، ولم يكتمل الإرسال'), findsOneWidget);
      expect(store.retained, isNotNull);
    });
  });

  group('the happy path', () {
    testWidgets('token, SDK, upload, then Stage 9 — and the pointer advances', (tester) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);

      await startScan(tester);

      expect(api.issueTokenCallCount, 1);
      expect(scanner.enrollCallCount, 1);
      expect(api.submitScanCallCount, 1);
      expect(landedPath(), '/stage-9');

      final progress = await (db.select(
        db.localProgress,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(progress.resumeStage, 'stage9');
    });

    testWidgets('the JWS is forwarded untouched and never decoded on the device', (tester) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      scanner.jws = 'aaa.bbb.ccc';

      await startScan(tester);

      expect(api.lastSubmitScanArgs!['jws'], 'aaa.bbb.ccc');
    });
  });

  group('BL-037 — a refused scan is not a generic error', () {
    testWidgets('SCAN_REJECTED shows the rescan screen and says an attempt was used', (
      tester,
    ) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrow = const ScanRejectedException();

      await startScan(tester);

      expect(find.text('لم ينجح مسح الوثيقة'), findsOneWidget);
      expect(find.textContaining('تم احتساب محاولة'), findsOneWidget);
      // Both of customer.md's two offers, and no third.
      expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'تغيير الوثيقة'), findsOneWidget);
    });

    testWidgets('an uncoded 400 is a client error, and does not claim an attempt was spent', (
      tester,
    ) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrow = const IdentityScanClientErrorException();

      await startScan(tester);

      expect(find.textContaining('تم احتساب محاولة'), findsNothing);
    });
  });

  group('an SDK session that produced no JWS', () {
    testWidgets('USER_CANCEL files the cancel, which is what spends the attempt', (tester) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      scanner.errorToThrow = const UqudoScanFailure('USER_CANCEL');

      await startScan(tester);

      expect(api.cancelScanCallCount, 1);
      expect(api.lastCancelScanArgs!['documentType'], IdentityDocumentTypes.passport);
      expect(find.text('لم ينجح مسح الوثيقة'), findsOneWidget);
    });

    testWidgets('a missing camera permission spends NOTHING and never calls cancel', (
      tester,
    ) async {
      // The one class of SDK failure that consumed no Uqudo operation. Charging a three-attempt
      // budget for a device permission the customer can fix in Settings would be wrong.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      scanner.errorToThrow = const UqudoScanFailure(
        'SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED',
      );

      await startScan(tester);

      expect(api.cancelScanCallCount, 0);
      expect(find.text('لا يمكن الوصول إلى الكاميرا'), findsOneWidget);
      expect(find.textContaining('لم يتم احتساب أي محاولة'), findsOneWidget);
    });

    testWidgets('a cancel that could not be FILED must not claim an attempt was counted', (
      tester,
    ) async {
      // Found by @agent-reviewer against this diff: this branch used to show the rejected screen,
      // whose body reads "تم احتساب محاولة" — stating as fact something the code's own comment
      // says did not happen, because the backend owns the budget and never recorded the attempt.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      scanner.errorToThrow = const UqudoScanFailure('USER_CANCEL');
      api.cancelScanErrorToThrow = const BackendUnreachableException('offline');

      await startScan(tester);

      expect(find.textContaining('تم احتساب محاولة'), findsNothing);
      expect(find.textContaining('لم يتم احتساب أي محاولة'), findsOneWidget);
    });

    testWidgets('a cancel that exhausts the budget surfaces the block, not a rescan offer', (
      tester,
    ) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      scanner.errorToThrow = const UqudoScanFailure('USER_CANCEL');
      api.cancelScanErrorToThrow = ScanConflictException(
        ScanConflictCode.scanBlocked,
        blockedUntil: DateTime.now().add(const Duration(hours: 24)),
      );

      await startScan(tester);

      expect(find.text('مسح الوثيقة غير متاح مؤقتًا'), findsOneWidget);
    });

    testWidgets('an SDK that never answers spends the attempt instead of spinning forever', (
      tester,
    ) async {
      // F-1, the cancel-hang. The pinned plugin leaves `pendingResult` unresolved on a null-Intent
      // RESULT_CANCELED — an ordinary system back-press — so the enrol future never settles and
      // every arm of this screen keys off a throw. Before the timeout existed this was an
      // unbounded spinner with no button, no back affordance and no route out.
      //
      // Deliberately NOT using the `startScan` helper: it calls `pumpAndSettle`, and while the
      // spinner is up the progress indicator schedules frames forever, so settling would time out
      // rather than reach anything.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      scanner.pendingEnroll = Completer<String>();

      await tester.tap(find.widgetWithText(FilledButton, 'ابدأ المسح'));
      await tester.pump();

      // The hang exactly as it was: SDK launched, nothing filed, spinner up.
      expect(scanner.enrollCallCount, 1);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(api.cancelScanCallCount, 0);

      // The SHIPPED duration, under the widget tester's fake clock — no real three-minute wait.
      await tester.pump(uqudoScanNoAnswerTimeout + const Duration(seconds: 1));
      await tester.pumpAndSettle();

      expect(find.byType(CircularProgressIndicator), findsNothing);
      // A launched session consumes a real Uqudo operation whether or not it ever answered, so it
      // costs what a cancel costs (customer.md:656-659).
      expect(api.cancelScanCallCount, 1);
      expect(find.text('لم ينجح مسح الوثيقة'), findsOneWidget);
      // ...and because an attempt WAS spent, the screen must not tell the customer otherwise. A
      // bare TimeoutException falling through to `catch (_)` would have shown exactly that.
      expect(find.textContaining('لم يتم احتساب أي محاولة'), findsNothing);
    });
  });

  group('connectivity is never charged to the budget', () {
    testWidgets('a failed token request says so in words and never launches the SDK', (
      tester,
    ) async {
      // customer.md: "Token request fails, or the JWS upload fails → a connectivity failure, not a
      // scan failure, and not counted against the retry budget." The 500 from a down Uqudo token
      // endpoint arrives here too, mapped to BackendUnreachableException by DioIdentityScanApi.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.issueTokenErrorToThrow = const BackendUnreachableException('HTTP 500');

      await startScan(tester);

      expect(scanner.enrollCallCount, 0);
      expect(find.text('تعذر الاتصال'), findsOneWidget);
      expect(find.textContaining('لم يتم احتساب أي محاولة'), findsOneWidget);
    });
  });

  group('BL-034 — a dropped upload retries, it does not rescan', () {
    testWidgets('a lost acknowledgement offers re-send, not re-scan', (tester) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrowOnce = const BackendUnreachableException('connection reset');

      await startScan(tester);

      expect(find.text('تم مسح الوثيقة، ولم يكتمل الإرسال'), findsOneWidget);
      expect(find.textContaining('لا حاجة لإعادة المسح'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'إعادة الإرسال'), findsOneWidget);
    });

    testWidgets('the retry re-posts the identical submission and never re-opens the SDK', (
      tester,
    ) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrowOnce = const BackendUnreachableException('connection reset');

      await startScan(tester);
      await tester.tap(find.widgetWithText(FilledButton, 'إعادة الإرسال'));
      await tester.pumpAndSettle();

      expect(api.submitScanCalls, hasLength(2));
      expect(api.submitScanCalls[1], equals(api.submitScanCalls[0]));
      expect(scanner.enrollCallCount, 1);
      expect(landedPath(), '/stage-9');
    });

    testWidgets('an expired artifact says the capture is unusable and asks for a rescan', (
      tester,
    ) async {
      // customer.md Stage 13 requires the app to distinguish "retrying an upload" from "this
      // capture can no longer be used" and say plainly which happened.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrow = const ScanConflictException(ScanConflictCode.artifactExpired);

      await startScan(tester);

      expect(find.text('انتهت صلاحية المسح السابق'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'مسح الوثيقة من جديد'), findsOneWidget);
    });
  });

  group('the conflict codes each drive their own screen', () {
    testWidgets('SCAN_TYPE_EXHAUSTED offers the other document type', (tester) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.issueTokenErrorToThrow = const ScanConflictException(
        ScanConflictCode.scanTypeExhausted,
      );

      await startScan(tester);

      expect(find.widgetWithText(FilledButton, 'اختيار وثيقة أخرى'), findsOneWidget);
      await tester.tap(find.widgetWithText(FilledButton, 'اختيار وثيقة أخرى'));
      await tester.pumpAndSettle();
      expect(landedPath(), '/stage-7');
    });

    testWidgets('PROFILE_TERMINAL ends the journey', (tester) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.issueTokenErrorToThrow = const ScanConflictException(ScanConflictCode.profileTerminal);

      await startScan(tester);

      expect(landedPath(), '/ended');
    });

    testWidgets('REGISTRY_PENDING sends the customer forward to Stage 9, not back to a rescan', (
      tester,
    ) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrow = const ScanConflictException(ScanConflictCode.registryPending);

      await startScan(tester);

      expect(landedPath(), '/stage-9');
    });

    testWidgets('STATE_CONFLICT re-syncs through the read-only endpoint and lands on Stage 9', (
      tester,
    ) async {
      // The recovery STATE_CONFLICT advises, actually performed. S5-11's
      // POST /registry-review/current is what makes it possible: a read with no registry call and
      // no write.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrow = const ScanConflictException(ScanConflictCode.stateConflict);

      await startScan(tester);

      expect(api.currentReviewCallCount, 1);
      expect(api.retryRegistryLookupCallCount, 0);
      expect(landedPath(), '/stage-9');
    });

    testWidgets('a STATE_CONFLICT whose re-sync also fails falls back to the type selection', (
      tester,
    ) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrow = const ScanConflictException(ScanConflictCode.stateConflict);
      api.currentReviewErrorToThrow = const ScanConflictException(ScanConflictCode.stateConflict);

      await startScan(tester);

      expect(landedPath(), '/stage-7');
    });

    testWidgets('an unknown future code degrades to a re-sync rather than a wrong screen', (
      tester,
    ) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.submitScanErrorToThrow = const ScanConflictException(ScanConflictCode.unknown);

      await startScan(tester);

      expect(api.currentReviewCallCount, 1);
    });
  });

  group('SCAN_BLOCKED — the countdown copes with every shape blockedUntil can take', () {
    testWidgets('a future deadline renders a countdown and no retry button yet', (tester) async {
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.issueTokenErrorToThrow = ScanConflictException(
        ScanConflictCode.scanBlocked,
        blockedUntil: DateTime.now().add(const Duration(hours: 24)),
      );

      await startScan(tester);

      expect(find.text('مسح الوثيقة غير متاح مؤقتًا'), findsOneWidget);
      expect(find.textContaining('يمكنك المحاولة مرة أخرى بعد'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);
      // customer.md: the block applies to this stage only, and saying so is what stops it reading
      // as the loss of the whole session.
      expect(find.textContaining('كل ما أدخلته وتم التحقق منه محفوظ'), findsOneWidget);
    });

    testWidgets(
      'BL-114(b) — a deadline ALREADY ELAPSED when the server refused never claims a retry will work',
      (tester) async {
        // **This test asserted the opposite until S8-14**, and the behaviour it asserted was the
        // defect: it required «يمكنك المحاولة مرة أخرى الآن» — *you can try again now* — plus a
        // working-looking retry button, for a refusal whose own deadline says the wait is already
        // over. For a profile that has spent its lifetime token cap that sentence is false and
        // stays false forever, and the button can never succeed. The app has OBSERVED a refusal
        // after the wait ended; it may say that, and it may not promise a retry.
        final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
        api.issueTokenErrorToThrow = ScanConflictException(
          ScanConflictCode.scanBlocked,
          blockedUntil: DateTime.now().subtract(const Duration(hours: 3)),
        );

        await startScan(tester);

        expect(
          find.text(
            'انتهت مدة الانتظار، ومع ذلك لا يزال هذا الإجراء غير متاح. يرجى زيارة أقرب فرع.',
          ),
          findsOneWidget,
        );
        // The two things that made this a permanent loop: the false sentence and the dead button.
        expect(find.text('يمكنك المحاولة مرة أخرى الآن، أو زيارة أقرب فرع.'), findsNothing);
        expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);
        expect(find.textContaining('-'), findsNothing);
        // Ruling A (2026-09-11): no claim about limits, and manual completion is never named.
        expect(find.textContaining('الحد'), findsNothing);
        // There is still a way out of the screen — BL-109's lesson.
        expect(find.widgetWithText(OutlinedButton, 'العودة إلى البداية'), findsOneWidget);
      },
    );

    testWidgets('an ABSENT deadline gets its OWN copy — it must not claim a wait ended', (
      tester,
    ) async {
      // BL-049's shape: a blocked_scan profile whose scan_blocked_until is null. The controller
      // omits the member entirely, so the client sees no value — not a null-valued one.
      //
      // **The first version of the S8-14 fix folded this into the elapsed case**, and so told the
      // customer «انتهت مدة الانتظار» — *the waiting period has ended* — about a wait that was
      // never announced (found by `@agent-reviewer` on the diff). With no deadline the app cannot
      // count anything down, cannot claim a wait is over, and must not promise a retry either.
      final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
      api.issueTokenErrorToThrow = const ScanConflictException(ScanConflictCode.scanBlocked);

      await startScan(tester);

      expect(find.text('هذا الإجراء غير متاح حاليًا. يرجى زيارة أقرب فرع.'), findsOneWidget);
      expect(find.textContaining('انتهت مدة الانتظار'), findsNothing);
      expect(find.textContaining('يمكنك المحاولة مرة أخرى'), findsNothing);
      expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);
      // BL-109's lesson holds in every case: there is still a way off the screen.
      expect(find.widgetWithText(OutlinedButton, 'العودة إلى البداية'), findsOneWidget);
    });

    testWidgets(
      'BL-114(b) — a countdown that runs out IN FRONT of the customer still offers the retry',
      (tester) async {
        // The other half of the split, and the reason the fix is not simply "never offer a retry
        // on a past deadline". Nothing has been refused since this wait ended, and the retry is
        // exactly what lifts an ordinary block server-side, so offering it here is honest. Only a
        // refusal that ARRIVES already-elapsed proves retrying does not work.
        final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
        api.issueTokenErrorToThrow = ScanConflictException(
          ScanConflictCode.scanBlocked,
          blockedUntil: DateTime.now().add(const Duration(milliseconds: 150)),
        );

        await startScan(tester);
        expect(find.textContaining('يمكنك المحاولة مرة أخرى بعد'), findsOneWidget);
        expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);

        // REAL time has to pass, not fake: the widget reads `DateTime.now()`, which
        // `tester.pump(duration)` does not advance — it only fires pending timers. Waiting
        // inside `runAsync` is what actually moves the clock the widget is reading.
        await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 400)));
        // Real time moves the clock the widget READS; fake time moves the 1-second ticker that
        // makes it re-read. Both are needed, which is why this looks redundant and is not.
        await tester.pump(const Duration(seconds: 1));

        expect(find.text('يمكنك المحاولة مرة أخرى الآن، أو زيارة أقرب فرع.'), findsOneWidget);
        expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsOneWidget);
      },
    );

    testWidgets(
      'BL-114(b) — the loop terminates: retrying after the countdown and being refused again drops the retry',
      (tester) async {
        // The round trip the old screen could never leave. Before S8-14 the refusal came back with
        // another past deadline, the widget rebuilt into the same "try again now" state, and the
        // customer could tap for ever. Now the second refusal arrives already-elapsed and the
        // screen says so instead.
        final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
        api.issueTokenErrorToThrow = ScanConflictException(
          ScanConflictCode.scanBlocked,
          blockedUntil: DateTime.now().add(const Duration(milliseconds: 150)),
        );

        await startScan(tester);
        // Real time, for the same reason as the test above.
        await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 400)));
        // Real time moves the clock the widget READS; fake time moves the 1-second ticker that
        // makes it re-read. Both are needed, which is why this looks redundant and is not.
        await tester.pump(const Duration(seconds: 1));
        expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsOneWidget);

        // The refusal a capped profile actually gets: the stored deadline, already spent.
        api.issueTokenErrorToThrow = ScanConflictException(
          ScanConflictCode.scanBlocked,
          blockedUntil: DateTime.now().subtract(const Duration(hours: 3)),
        );
        await tester.tap(find.widgetWithText(FilledButton, 'إعادة المحاولة'));
        await tester.pumpAndSettle();

        expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);
        expect(
          find.text(
            'انتهت مدة الانتظار، ومع ذلك لا يزال هذا الإجراء غير متاح. يرجى زيارة أقرب فرع.',
          ),
          findsOneWidget,
        );
      },
    );
  });

  testWidgets('no screen in Stage 8 ever displays an attempt count', (tester) async {
    // R-052 / AD-002a: attemptsRemaining is deliberately not on the wire, and a locally-counted
    // substitute is exactly the anti-pattern the server-side budget exists to prevent. This asserts
    // the absence directly rather than trusting the code review that put it there.
    final (:api, :scanner, :db, :landedPath) = await pumpStage8(tester);
    api.submitScanErrorToThrow = const ScanRejectedException();

    await startScan(tester);

    for (final forbidden in ['محاولتان', 'محاولات متبقية', 'متبقي', '3 محاولات', '2 محاولة']) {
      expect(find.textContaining(forbidden), findsNothing, reason: 'must not count attempts');
    }
  });
}
