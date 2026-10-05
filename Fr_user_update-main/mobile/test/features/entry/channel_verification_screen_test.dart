import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/dataentry/data_entry_providers.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/entry/entry_providers.dart';
import 'package:mobile/core/reference/reference_providers.dart';
import 'package:mobile/features/entry/channel_verification_screen.dart';

import '../../core/dataentry/fake_data_entry_api.dart';
import '../../core/dataentry/seed_data_entry_catalog.dart';
import '../../core/entry/fake_entry_api.dart';
import '../../core/reference/fake_reference_api.dart';

void main() {
  /// Seeds `LocalProgress` at `awaitingVerification` with three selected channels (two phone, one
  /// email) — mirrors what `ContactChannelsScreen`'s submit leaves behind, and what step 5 of the
  /// task requires ("three channels selected at 1b, three rows").
  Future<SessionDatabase> seedThreeChannels() async {
    final db = SessionDatabase.forTesting();
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '2',
            verifiedAccountNumber: '12345',
            resumeStage: 'awaitingVerification',
            profileId: const Value('p1'),
            channelsSummary: const Value(
              'sms:unverified:••••1234|whatsapp:unverified:••••1234|email:unverified:a•••@x.com',
            ),
            updatedAt: DateTime.now(),
          ),
        );
    return db;
  }

  Future<({FakeEntryApi api, String? Function() landedPath, SessionDatabase sessionDb})>
  pumpWithRouter(WidgetTester tester, {SessionDatabase? db, bool seedCatalog = true}) async {
    final sessionDb = db ?? await seedThreeChannels();
    addTearDown(sessionDb.close);
    final fakeApi = FakeEntryApi();
    final fakeReferenceApi = FakeReferenceApi();
    if (seedCatalog) seedDataEntryCatalog(fakeReferenceApi);
    final referenceDb = ReferenceDatabase.forTesting();
    addTearDown(referenceDb.close);
    String? landedPath;
    final router = GoRouter(
      initialLocation: '/channel-verification',
      routes: [
        GoRoute(
          path: '/channel-verification',
          builder: (context, state) =>
              ChannelVerificationScreen(offline: (state.extra as bool?) ?? false),
        ),
        GoRoute(
          path: '/contact-channels',
          builder: (context, state) {
            landedPath = '/contact-channels';
            return const Scaffold(body: Text('contact channels'));
          },
        ),
        GoRoute(
          path: '/session-pending',
          builder: (context, state) {
            landedPath = '/session-pending';
            return const Scaffold(body: Text('session pending'));
          },
        ),
        GoRoute(
          path: '/stage-3',
          builder: (context, state) {
            landedPath = '/stage-3';
            return const Scaffold(body: Text('stage 3'));
          },
        ),
        GoRoute(
          path: '/terminal',
          builder: (context, state) {
            landedPath = '/terminal';
            return Scaffold(body: Text('terminal: ${state.extra}'));
          },
        ),
        GoRoute(
          path: '/account-entry',
          builder: (context, state) {
            landedPath = '/account-entry';
            return const Scaffold(body: Text('account entry'));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(sessionDb),
          entryApiProvider.overrideWithValue(fakeApi),
          // The Stage 2→3 boundary (S5-05) calls `DataEntryRepository.prepareCatalog()`, which
          // needs a working reference-data pipeline — seeded here rather than left to hit a real
          // network, matching this suite's existing no-real-network discipline.
          referenceApiProvider.overrideWithValue(fakeReferenceApi),
          referenceDatabaseProvider.overrideWithValue(referenceDb),
          dataEntryApiProvider.overrideWithValue(FakeDataEntryApi()),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (api: fakeApi, landedPath: () => landedPath, sessionDb: sessionDb);
  }

  Finder rowOf(String channel) => find.byKey(ValueKey('row_$channel'));
  Finder codeFieldOf(String channel) =>
      find.descendant(of: rowOf(channel), matching: find.byType(TextField));
  Finder verifyButtonOf(String channel) =>
      find.descendant(of: rowOf(channel), matching: find.widgetWithText(FilledButton, 'تحقق'));
  // Keyed rather than by-type: since BL-101 the email row carries a second `TextButton` (the
  // correction affordance), so `find.byType(TextButton)` inside that row matches two widgets.
  Finder resendButtonOf(String channel) => find.byKey(ValueKey('resend_$channel'));

  /// Scrolls the row into view before tapping it.
  ///
  /// Two tests in this file already did this by hand for the email row; the journey progress
  /// strip (walk comments 1/5, 2026-09-10) added 44 dp to the top of the screen and pushed the
  /// lower rows under the fixed action block, so the rest need it too. A missed tap here is
  /// SILENT — `tap` warns and carries on, the resend never happens, and the failure surfaces
  /// several assertions later as a route that was never taken.
  Future<void> tapResend(WidgetTester tester, String channel) async {
    await tester.ensureVisible(resendButtonOf(channel));
    await tester.pumpAndSettle();
    await tester.tap(resendButtonOf(channel));
  }

  Future<void> enterAndVerify(WidgetTester tester, String channel, String code) async {
    await tester.ensureVisible(rowOf(channel));
    await tester.pumpAndSettle();
    await tester.enterText(codeFieldOf(channel), code);
    await tester.pump();
    await tester.tap(verifyButtonOf(channel));
    await tester.pumpAndSettle();
  }

  testWidgets('three selected channels render three rows', (tester) async {
    await pumpWithRouter(tester);

    expect(rowOf('sms'), findsOneWidget);
    expect(rowOf('whatsapp'), findsOneWidget);
    expect(rowOf('email'), findsOneWidget);
  });

  testWidgets(
    'verifying one channel enables Next; entering a code in one row only calls verifyChannel '
    'for that row\'s own channel',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );

      expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'التالي')).onPressed, isNull);

      await enterAndVerify(tester, 'sms', '123456');

      expect(api.lastVerifiedChannel, 'sms');
      expect(api.lastVerifiedCode, '123456');
      expect(api.verifyChannelCallCount, 1);
      expect(find.text('موثقة — سيتم حفظها في ملفك'), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'التالي')).onPressed,
        isNotNull,
      );
    },
  );

  testWidgets(
    'verifying the email row alone never enables Next — only a phone channel does '
    '(regression, S5-04 review)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'email',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );

      await enterAndVerify(tester, 'email', '123456');

      expect(find.text('موثقة — سيتم حفظها في ملفك'), findsOneWidget); // the email row itself
      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'التالي')).onPressed,
        isNull,
      );
    },
  );

  testWidgets(
    'the status line and dialog use invariant Arabic phrasing, correct whether one or two '
    'channels remain unverified (regression for a dual/plural agreement bug, S5-04 review)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );

      // One verified, two unverified (whatsapp + email).
      await enterAndVerify(tester, 'sms', '123456');
      expect(find.textContaining('تم التحقق من: الرسائل النصية'), findsOneWidget);
      expect(find.textContaining('باقي القنوات'), findsOneWidget);
      expect(find.textContaining('منهما'), findsNothing); // the old dual-form bug
      expect(find.textContaining('تُحفظا'), findsNothing);

      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'whatsapp',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );
      // Two verified, one unverified (email) — tap Next to render the dialog's own text.
      await enterAndVerify(tester, 'whatsapp', '123456');
      await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
      await tester.pump();

      expect(find.textContaining('لم يتم التحقق من: البريد الإلكتروني'), findsOneWidget);
      // The old plural-noun title ("القنوات التالية"), wrong for a single unverified channel.
      expect(find.textContaining('القنوات التالية'), findsNothing);
    },
  );

  testWidgets(
    'a channel already locked from an earlier verify sequence still routes to /terminal once '
    'both phone rows are known-locked via resend (regression, S5-04 review, second pass — '
    'resend never causes a lock itself, only surfaces one already set by wrong-code verify '
    'attempts)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);

      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'sms',
        outcome: OtpResendOutcome.channelLocked,
        maskedDestination: '••••1234',
        secondsUntilAllowed: null,
      );
      await tapResend(tester, 'sms');
      await tester.pump();
      await tester.pump();
      expect(landedPath(), isNull); // only one phone channel locked so far

      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'whatsapp',
        outcome: OtpResendOutcome.channelLocked,
        maskedDestination: '••••1234',
        secondsUntilAllowed: null,
      );
      await tapResend(tester, 'whatsapp');
      await tester.pump();
      await tester.pump();

      expect(landedPath(), '/terminal');
    },
  );

  testWidgets('a wrong code fails only that row — both remain retryable', (tester) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.verifyChannelResultToServe = const OtpVerifyResult(
      channel: 'sms',
      outcome: OtpVerifyOutcome.wrongCode,
      state: ChannelState.unverified,
      sessionBlockedUntil: null,
    );

    await enterAndVerify(tester, 'sms', '000000');

    expect(find.textContaining('الرمز غير صحيح'), findsOneWidget);
    // Both the just-failed row and the untouched row still show their verify control.
    expect(verifyButtonOf('sms'), findsOneWidget);
    expect(verifyButtonOf('whatsapp'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(verifyButtonOf('whatsapp')).onPressed,
      isNull, // still disabled — no code typed yet, but present and not locked
    );
  });

  testWidgets('CHANNEL_LOCKED disables only that row, others stay retryable', (tester) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.verifyChannelResultToServe = const OtpVerifyResult(
      channel: 'sms',
      outcome: OtpVerifyOutcome.channelLocked,
      state: ChannelState.unverified,
      sessionBlockedUntil: null,
    );

    await enterAndVerify(tester, 'sms', '000000');

    expect(find.textContaining('تم قفل هذه القناة'), findsOneWidget);
    expect(tester.widget<TextField>(codeFieldOf('sms')).enabled, isFalse);
    // The other phone row is untouched and still enabled.
    expect(tester.widget<TextField>(codeFieldOf('whatsapp')).enabled, isTrue);
  });

  testWidgets(
    'sessionBlockedUntilIso non-null (every phone channel locked) navigates to /terminal',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.verifyChannelResultToServe = OtpVerifyResult(
        channel: 'whatsapp',
        outcome: OtpVerifyOutcome.channelLocked,
        state: ChannelState.unverified,
        sessionBlockedUntil: DateTime.now().add(const Duration(minutes: 15)),
      );

      await enterAndVerify(tester, 'whatsapp', '000000');

      expect(landedPath(), '/terminal');
      expect(find.textContaining('15 دقيقة'), findsOneWidget); // 11+ takes the singular form
      // Local state is deliberately left intact — a relaunch should resume back into Stage 2.
      expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNotNull);
    },
  );

  testWidgets(
    'the terminal message uses the Arabic plural "دقائق" for 3–10 minutes remaining, not the '
    'singular "دقيقة" (regression, S5-04 review, second pass)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.verifyChannelResultToServe = OtpVerifyResult(
        channel: 'whatsapp',
        outcome: OtpVerifyOutcome.channelLocked,
        state: ChannelState.unverified,
        sessionBlockedUntil: DateTime.now().add(const Duration(minutes: 3)),
      );

      await enterAndVerify(tester, 'whatsapp', '000000');

      expect(landedPath(), '/terminal');
      expect(find.textContaining('3 دقائق'), findsOneWidget);
      expect(find.textContaining('3 دقيقة'), findsNothing);
    },
  );

  testWidgets('resend TOO_SOON shows the authoritative countdown, disables resend only', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.resendChannelResultToServe = const OtpResendResult(
      channel: 'sms',
      outcome: OtpResendOutcome.tooSoon,
      maskedDestination: '••••1234',
      secondsUntilAllowed: 27,
    );

    // A plain `pump()`, not `pumpAndSettle()`: the countdown is driven by a real periodic ticker
    // that `testWidgets`' fake clock advances on every pumped duration — `pumpAndSettle()`'s
    // repeated 100ms pumps would themselves tick the countdown down before this assertion runs.
    await tapResend(tester, 'sms');
    await tester.pump();
    await tester.pump();

    expect(find.textContaining('27'), findsOneWidget);
    expect(tester.widget<TextButton>(resendButtonOf('sms')).onPressed, isNull);
    // Verify is untouched by a resend outcome.
    expect(tester.widget<TextField>(codeFieldOf('sms')).enabled, isTrue);
  });

  testWidgets('resend CAP_EXHAUSTED disables resend only, code stays enterable', (tester) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.resendChannelResultToServe = const OtpResendResult(
      channel: 'sms',
      outcome: OtpResendOutcome.capExhausted,
      maskedDestination: '••••1234',
      secondsUntilAllowed: null,
    );

    await tapResend(tester, 'sms');
    await tester.pumpAndSettle();

    expect(find.textContaining('تم استنفاد إعادة الإرسال'), findsOneWidget);
    expect(tester.widget<TextButton>(resendButtonOf('sms')).onPressed, isNull);
    expect(tester.widget<TextField>(codeFieldOf('sms')).enabled, isTrue);
  });

  testWidgets(
    'Next with an unverified selected channel shows the confirmation dialog exactly once, '
    '"go back" listed first',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );
      await enterAndVerify(tester, 'sms', '123456');

      await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
      await tester.pump();

      expect(find.byType(AlertDialog), findsOneWidget);
      final dialog = tester.widget<AlertDialog>(find.byType(AlertDialog));
      final actionLabels = dialog.actions!
          .map((w) => (w as TextButton).child)
          .map((child) => (child as Text).data)
          .toList();
      expect(actionLabels.first, 'العودة والتحقق');
      expect(landedPath(), isNull); // still on Stage 2, dialog is modal
    },
  );

  testWidgets('Next with every selected channel verified shows no dialog, navigates directly', (
    tester,
  ) async {
    final db = SessionDatabase.forTesting();
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '2',
            verifiedAccountNumber: '12345',
            resumeStage: 'awaitingVerification',
            profileId: const Value('p1'),
            channelsSummary: const Value('sms:verified:••••1234'),
            updatedAt: DateTime.now(),
          ),
        );
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester, db: db);

    await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
    await tester.pumpAndSettle();

    expect(find.byType(AlertDialog), findsNothing);
    expect(landedPath(), '/stage-3');
    final progress = await (sessionDb.select(sessionDb.localProgress)).getSingle();
    expect(progress.resumeStage, 'stage3');
  });

  testWidgets(
    'when prepareCatalog finds no usable cache at all, Next shows an error and stays on '
    'Stage 2 — never lets the customer into a broken Stage 3 (S5-05 review: this blocking '
    'branch was untested, only the success branch was)',
    (tester) async {
      final db = SessionDatabase.forTesting();
      await db
          .into(db.localProgress)
          .insertOnConflictUpdate(
            LocalProgressCompanion.insert(
              id: const Value(0),
              verifiedBranchCode: '2',
              verifiedAccountNumber: '12345',
              resumeStage: 'awaitingVerification',
              profileId: const Value('p1'),
              channelsSummary: const Value('sms:verified:••••1234'),
              updatedAt: DateTime.now(),
            ),
          );
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(
        tester,
        db: db,
        seedCatalog: false, // no manifest to serve — prepareCatalog can activate nothing
      );

      await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
      await tester.pumpAndSettle();

      expect(landedPath(), isNull); // still on Stage 2, never reached /stage-3
      expect(find.textContaining('تعذر تحميل بيانات القوائم المطلوبة'), findsOneWidget);
      final progress = await (sessionDb.select(sessionDb.localProgress)).getSingle();
      expect(progress.resumeStage, 'awaitingVerification'); // never advanced
    },
  );

  testWidgets('"phone number wrong" action returns to /contact-channels', (tester) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);

    final button = find.text('رقم الهاتف غير صحيح؟ العودة لتعديله');
    await tester.ensureVisible(button);
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(landedPath(), '/contact-channels');
  });

  testWidgets(
    'the "phone number wrong" action DISAPPEARS once a phone channel verifies (W-10 / 5d)',
    (tester) async {
      // Product-owner decision 2026-09-07, BL-091 item 1, built at S8-05. A number that has
      // demonstrably received and returned a code has authenticated the session, so the repair
      // path closes.
      //
      // **This test pins a REVERSAL of a written journey rule.** customer.md Stage 2
      // "Corrections" listed «Phone number wrong -> Back to 1b» unconditionally; it is now
      // available only until a phone channel verifies, and the journey document was amended in
      // the same commit. Without this test the reversal is unpinned and a future edit restoring
      // the unconditional link would pass every gate.
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );

      expect(find.text('رقم الهاتف غير صحيح؟ العودة لتعديله'), findsOneWidget);

      await enterAndVerify(tester, 'sms', '123456');

      expect(find.text('رقم الهاتف غير صحيح؟ العودة لتعديله'), findsNothing);
    },
  );

  // ---------------------------------------------------------------------------------------
  // BL-101 / BL-098 — in-place email correction. customer.md Stage 2 "Corrections".
  // ---------------------------------------------------------------------------------------

  Finder correctionAffordance() => find.byKey(const ValueKey('correct_email_start'));
  Finder correctedEmailField() => find.byKey(const ValueKey('corrected_email'));
  Finder correctionSave() => find.byKey(const ValueKey('corrected_email_save'));

  /// Opens the email row's correction editor and types [address] into it.
  Future<void> beginCorrection(WidgetTester tester, String address) async {
    await tester.ensureVisible(correctionAffordance());
    await tester.pumpAndSettle();
    await tester.tap(correctionAffordance());
    await tester.pumpAndSettle();
    await tester.enterText(correctedEmailField(), address);
    await tester.pump();
  }

  testWidgets(
    'the correction affordance is offered on the email row and on no phone row — changing a '
    'phone number is a Stage 1b re-entry, not a field on this call',
    (tester) async {
      await pumpWithRouter(tester);

      expect(correctionAffordance(), findsOneWidget);
      expect(
        find.descendant(of: rowOf('email'), matching: correctionAffordance()),
        findsOneWidget,
      );
      expect(find.descendant(of: rowOf('sms'), matching: correctionAffordance()), findsNothing);
      expect(
        find.descendant(of: rowOf('whatsapp'), matching: correctionAffordance()),
        findsNothing,
      );
    },
  );

  testWidgets(
    'correcting a mistyped address sends it on the resend, updates the row mask, and says a '
    'code was sent',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.issued,
        maskedDestination: 'n•••.ahmed@e••••••.sd',
        secondsUntilAllowed: null,
      );

      await beginCorrection(tester, 'nour.ahmed@example.sd');
      await tester.tap(correctionSave());
      await tester.pumpAndSettle();

      expect(api.lastResendedCorrectedEmail, 'nour.ahmed@example.sd');
      expect(api.lastResendedChannel, 'email');
      expect(find.text('n•••.ahmed@e••••••.sd'), findsOneWidget);
      expect(find.text('تم تحديث البريد الإلكتروني وإرسال رمز جديد.'), findsOneWidget);
      // The editor closed behind the save.
      expect(correctedEmailField(), findsNothing);
    },
  );

  testWidgets(
    'an ordinary resend carries no corrected address — the field is absent, not empty',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.issued,
        maskedDestination: 'a•••@x.com',
        secondsUntilAllowed: null,
      );

      await tapResend(tester, 'email');
      await tester.pumpAndSettle();

      expect(api.resendChannelCallCount, 1);
      expect(api.lastResendedCorrectedEmail, isNull);
    },
  );

  testWidgets(
    'a correction saved during a resend countdown must not claim a code was sent — the backend '
    'applies the correction and refuses the resend, so the label and the notice both say so',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);

      // Drive the row into a live countdown first.
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.tooSoon,
        maskedDestination: 'a•••@x.com',
        secondsUntilAllowed: 28,
      );
      await tapResend(tester, 'email');
      // Bare pumps — `pumpAndSettle` would run the 1-second ticker down to zero and end the
      // countdown this test is about.
      await tester.pump();
      await tester.pump();

      await beginCorrection(tester, 'nour.ahmed@example.sd');

      // The action itself promises only what the backend will actually do.
      expect(find.text('حفظ البريد الجديد فقط'), findsOneWidget);
      expect(find.text('حفظ البريد الجديد وإعادة إرسال الرمز'), findsNothing);

      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.tooSoon,
        maskedDestination: 'n•••.ahmed@e••••••.sd',
        secondsUntilAllowed: 28,
      );
      await tester.tap(correctionSave());
      await tester.pump();
      await tester.pump();

      expect(api.lastResendedCorrectedEmail, 'nour.ahmed@example.sd');
      // The correction landed: the new mask is shown.
      expect(find.text('n•••.ahmed@e••••••.sd'), findsOneWidget);
      // But nothing was sent, and the copy says exactly that.
      expect(
        find.text('تم تحديث البريد الإلكتروني. لم يتم إرسال رمز جديد — إعادة الإرسال بعد 28 ث.'),
        findsOneWidget,
      );
      expect(find.text('تم تحديث البريد الإلكتروني وإرسال رمز جديد.'), findsNothing);
    },
  );

  testWidgets(
    'the "no code sent yet" notice re-renders its countdown as the ticker runs down, rather '
    'than freezing at the value the refusal reported',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.tooSoon,
        maskedDestination: 'n•••@y.com',
        secondsUntilAllowed: 3,
      );

      await beginCorrection(tester, 'nour@y.com');
      await tester.tap(correctionSave());
      await tester.pump();
      await tester.pump();

      expect(
        find.text('تم تحديث البريد الإلكتروني. لم يتم إرسال رمز جديد — إعادة الإرسال بعد 3 ث.'),
        findsOneWidget,
      );

      // Two ticks later the same line must have moved with the clock, not stayed at 3.
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(seconds: 1));

      expect(
        find.text('تم تحديث البريد الإلكتروني. لم يتم إرسال رمز جديد — إعادة الإرسال بعد 3 ث.'),
        findsNothing,
      );
      expect(
        find.text('تم تحديث البريد الإلكتروني. لم يتم إرسال رمز جديد — إعادة الإرسال بعد 1 ث.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'a cap arriving while the editor is OPEN closes it, rather than leaving an enabled-looking '
    'save button that would be silently dropped (regression, @agent-reviewer S8-10)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);

      await beginCorrection(tester, 'nour@y.com');
      expect(correctedEmailField(), findsOneWidget);

      // The resend link is still on screen beneath the open editor; tapping it exhausts the cap.
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.capExhausted,
        maskedDestination: 'a•••@x.com',
        secondsUntilAllowed: null,
      );
      await tapResend(tester, 'email');
      await tester.pump();
      await tester.pump();

      expect(correctedEmailField(), findsNothing);
      expect(correctionSave(), findsNothing);
      expect(correctionAffordance(), findsNothing);
    },
  );

  testWidgets(
    'a correction that exhausts the cap says the channel can no longer be verified this '
    'session, not that a code has not been sent YET (regression, @agent-reviewer S8-10)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.capExhausted,
        maskedDestination: 'n•••@y.com',
        secondsUntilAllowed: null,
      );

      await beginCorrection(tester, 'nour@y.com');
      await tester.tap(correctionSave());
      await tester.pump();
      await tester.pump();

      expect(
        find.text('تم تحديث البريد الإلكتروني، ولا يمكن إرسال رمز جديد لهذه القناة في هذه الجلسة.'),
        findsOneWidget,
      );
      expect(find.text('تم تحديث البريد الإلكتروني. لم يتم إرسال رمز جديد.'), findsNothing);
    },
  );

  testWidgets('an empty or over-long corrected address is refused without a network call', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);

    await beginCorrection(tester, '   ');
    await tester.tap(correctionSave());
    await tester.pump();
    expect(find.text('أدخل البريد الإلكتروني.'), findsOneWidget);
    expect(api.resendChannelCallCount, 0);

    // 254 is the backend's own limit (RFC 5321 §4.5.3.1.3); 256 must not reach it.
    await tester.enterText(correctedEmailField(), '${'a' * 250}@x.com');
    await tester.pump();
    await tester.tap(correctionSave());
    await tester.pump();
    expect(find.text('البريد الإلكتروني طويل جداً.'), findsOneWidget);
    expect(api.resendChannelCallCount, 0);
  });

  testWidgets(
    'the correction notice does not outlive the code it described — verifying clears it, so the '
    'row never shows "a new code was sent" beside "موثقة" (regression, @agent-reviewer S8-10)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.issued,
        maskedDestination: 'n•••@y.com',
        secondsUntilAllowed: null,
      );
      await beginCorrection(tester, 'nour@y.com');
      await tester.tap(correctionSave());
      await tester.pumpAndSettle();
      expect(find.text('تم تحديث البريد الإلكتروني وإرسال رمز جديد.'), findsOneWidget);

      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'email',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );
      await enterAndVerify(tester, 'email', '123456');

      expect(find.text('تم تحديث البريد الإلكتروني وإرسال رمز جديد.'), findsNothing);
    },
  );

  // NOT covered by a test: the `widget.offline` term on the save action. This harness always
  // pumps with `offline: false` (the route carries no `extra`), and every other offline behaviour
  // on this screen is uncovered for the same reason — adding an offline path to `pumpWithRouter`
  // is a change to shared test infrastructure that belongs with whoever covers the rest of it.

  testWidgets('cancelling the correction closes the editor and sends nothing', (tester) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);

    await beginCorrection(tester, 'nour@y.com');
    expect(correctedEmailField(), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('corrected_email_cancel')));
    await tester.pumpAndSettle();

    expect(correctedEmailField(), findsNothing);
    expect(correctionAffordance(), findsOneWidget);
    expect(api.resendChannelCallCount, 0);
  });

  testWidgets(
    'BL-098 — the correction affordance is withheld once resends are exhausted, because '
    'correcting there would invalidate the last usable code with no way to obtain another',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      expect(correctionAffordance(), findsOneWidget);

      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'email',
        outcome: OtpResendOutcome.capExhausted,
        maskedDestination: 'a•••@x.com',
        secondsUntilAllowed: null,
      );
      await tapResend(tester, 'email');
      await tester.pumpAndSettle();

      expect(find.text('تم استنفاد إعادة الإرسال'), findsOneWidget);
      expect(correctionAffordance(), findsNothing);
    },
  );

  testWidgets('BL-098 — the correction affordance is withheld on a locked email channel', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    expect(correctionAffordance(), findsOneWidget);

    api.verifyChannelResultToServe = const OtpVerifyResult(
      channel: 'email',
      outcome: OtpVerifyOutcome.channelLocked,
      state: ChannelState.unverified,
      sessionBlockedUntil: null,
    );
    await enterAndVerify(tester, 'email', '000000');

    expect(correctionAffordance(), findsNothing);
  });

  testWidgets('the correction affordance disappears once the email channel verifies', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    expect(correctionAffordance(), findsOneWidget);

    api.verifyChannelResultToServe = const OtpVerifyResult(
      channel: 'email',
      outcome: OtpVerifyOutcome.verified,
      state: ChannelState.verified,
      sessionBlockedUntil: null,
    );
    await enterAndVerify(tester, 'email', '123456');

    expect(correctionAffordance(), findsNothing);
  });

  // The four bands Arabic numeral–noun agreement distinguishes: n == 1, n == 2, 3-10, and 11+.
  // «ث» is a unit SYMBOL, not a counted noun, so it does not inflect across them the way a
  // spelled-out «ثانية» would — this asserts that at each boundary rather than leaving it to
  // reasoning. See `_secondsAr`'s doc comment for why routing it through `ArabicNounAgreement`
  // would be actively wrong here («بعد» governs the genitive, which the helper cannot express).
  for (final seconds in [1, 2, 5, 11]) {
    testWidgets('the resend countdown renders «$seconds ث» unchanged at n == $seconds', (
      tester,
    ) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.resendChannelResultToServe = OtpResendResult(
        channel: 'sms',
        outcome: OtpResendOutcome.tooSoon,
        maskedDestination: '••••1234',
        secondsUntilAllowed: seconds,
      );

      // Bare pumps, never `pumpAndSettle`: the screen's 1-second ticker would otherwise run the
      // countdown all the way to zero before the frame settles, and the label under test would
      // have already reverted. Matches this suite's existing resend tests.
      await tapResend(tester, 'sms');
      await tester.pump();
      await tester.pump();

      expect(find.text('إعادة الإرسال بعد $seconds ث'), findsOneWidget);
    });
  }

  testWidgets('an abandon action clears local state and returns to /account-entry', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);

    await tester.tap(find.text('التراجع عن الجلسة'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/account-entry');
    expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNull);
  });
}
