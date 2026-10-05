import 'package:drift/drift.dart' hide isNull, isNotNull;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/entry/entry_providers.dart';
import 'package:mobile/features/entry/contact_channels_screen.dart';

import '../../core/entry/fake_entry_api.dart';

void main() {
  Future<void> pump(WidgetTester tester, {bool offline = false}) async {
    final sessionDb = SessionDatabase.forTesting();
    addTearDown(sessionDb.close);
    // Matches how this screen is actually reached in the app — never without a verified pair
    // (see `EntryRepository.verifiedAccount()`); without this, `canSubmit`'s null-guard (G2, S5-02
    // second pass) would keep Next disabled regardless of what these tests do to the form.
    await sessionDb
        .into(sessionDb.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '2',
            verifiedAccountNumber: '12345',
            resumeStage: 'contactChannels',
            updatedAt: DateTime.now(),
          ),
        );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [sessionDatabaseProvider.overrideWithValue(sessionDb)],
        child: MaterialApp(
          home: Directionality(
            textDirection: TextDirection.rtl,
            child: ContactChannelsScreen(offline: offline),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  /// For submit tests: pre-seeds `LocalProgress` with the verified branch/account pair Stage 1a
  /// would have left (the screen reads branch/account from there, not `LocalDraft` — see
  /// `EntryRepository.verifiedAccount()`) and wires a real `GoRouter` so success/rejection
  /// navigation is observable.
  Future<({FakeEntryApi api, String? Function() landedPath, SessionDatabase sessionDb})>
  pumpWithRouter(
    WidgetTester tester,
  ) async {
    final sessionDb = SessionDatabase.forTesting();
    addTearDown(sessionDb.close);
    await sessionDb
        .into(sessionDb.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '2',
            verifiedAccountNumber: '12345',
            resumeStage: 'contactChannels',
            updatedAt: DateTime.now(),
          ),
        );
    final fakeApi = FakeEntryApi();
    String? landedPath;
    final router = GoRouter(
      initialLocation: '/contact-channels',
      routes: [
        GoRoute(
          path: '/contact-channels',
          builder: (context, state) => const ContactChannelsScreen(),
        ),
        GoRoute(
          path: '/channel-verification',
          builder: (context, state) {
            landedPath = '/channel-verification';
            return const Scaffold(body: Text('channel verification'));
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
          path: '/ended',
          builder: (context, state) {
            landedPath = '/ended';
            return const Scaffold(body: Text('ended'));
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

  group('the phone field — walk comment 6 (2026-09-10)', () {
    Finder phoneField() => find.widgetWithText(TextField, 'رقم الهاتف');

    /// The reported defect: the field took any length. Ten is the backend's own rule — a Sudan
    /// local number is a trunk `0` plus `PhoneNumberNormalizer.SUDAN_LOCAL_DIGITS_AFTER_ZERO` (9).
    testWidgets('accepts at most ten digits', (tester) async {
      await pump(tester);

      await tester.enterText(phoneField(), '09123456789999');
      await tester.pump();

      final controller = tester.widget<TextField>(phoneField()).controller!;
      expect(controller.text, '0912345678');
      expect(controller.text.length, 10);
    });

    /// Already true before this change, asserted here because the new length cap sits in the same
    /// formatter list and must not have displaced either of the two that were there.
    testWidgets('still refuses non-digits and still folds Arabic-Indic digits', (tester) async {
      await pump(tester);

      await tester.enterText(phoneField(), 'a0b9c1d2');
      await tester.pump();
      expect(tester.widget<TextField>(phoneField()).controller!.text, '0912');

      // ArabicDigitInputFormatter transliterates before anything reaches the wire (CLAUDE.md:
      // customers may TYPE Arabic-Indic digits).
      await tester.enterText(phoneField(), '٠٩١٢٣٤٥٦٧٨');
      await tester.pump();
      expect(tester.widget<TextField>(phoneField()).controller!.text, '0912345678');
    });

    /// The hint the customer asked for: the count in words, and a masked example.
    testWidgets('tells the customer the length, with a masked example', (tester) async {
      await pump(tester);

      final helper = tester.widget<TextField>(phoneField()).decoration!.helperText!;
      expect(helper, contains('عشرة خانات'));
      expect(helper, contains('0912'));
      expect(helper, contains('*'), reason: 'the example is masked, not a real number');
      // Isolated with FSI/PDI so the Latin digits do not reorder inside the Arabic sentence.
      // Written as escapes, never as the literal characters: they are invisible in source, and
      // `flutter analyze` rejects a raw bidi code point in a string literal outright.
      expect(helper, contains('\u2068'), reason: 'FSI opens the isolate');
      expect(helper, contains('\u2069'), reason: 'PDI closes it');
    });
  });

  testWidgets('deselecting both phone channels disables Next and shows the group error state', (
    tester,
  ) async {
    await pump(tester);

    expect(find.text('التالي'), findsOneWidget);
    // D8.4 dropped the Latin «(SMS)» gloss. This also removed an inconsistency: `channel_labels.dart`
    // has always called the channel «الرسائل النصية» with no gloss, so the checkbox was the only
    // place in the app naming it differently.
    final smsCheckbox = find.widgetWithText(CheckboxListTile, 'الرسائل النصية');
    final whatsappCheckbox = find.widgetWithText(CheckboxListTile, 'واتساب');

    // BL-086: WhatsApp now starts DESELECTED for the SMS-only pilot, so switching SMS off is on
    // its own enough to reach the both-phone-channels-off state this test is about. Asserting the
    // starting value first keeps the test honest about WHY only one tap is needed — a future
    // change back to default-on would fail here rather than silently making the tap a no-op.
    expect(tester.widget<CheckboxListTile>(whatsappCheckbox).value, isFalse);
    await tester.tap(smsCheckbox);
    await tester.pump();

    expect(find.text('يجب اختيار وسيلة واحدة على الأقل لاستلام الرمز'), findsOneWidget);

    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull);
  });

  testWidgets('the live consequence line updates as channels are toggled', (tester) async {
    await pump(tester);

    // Walk comment 4d replaced the old «سيتم حفظ في ملفك: …» sentence with this line plus the
    // ICONS of the channels that will actually be sent a code.
    //
    // BL-086: SMS is selected by default and WhatsApp is not, so the screen opens with one
    // verification target and names WhatsApp as the channel that will not be saved. The
    // «لن يتم حفظ…» clause is deliberately RETAINED under the new line — 4d offered to delete it,
    // but it is the only place the customer is told which details are not stored, so losing it
    // would be a silent drop of disclosure rather than a presentation change.
    expect(find.textContaining('سيتم إرسال رموز التحقق لاعتماد:'), findsOneWidget);
    expect(find.textContaining('لن يتم حفظ واتساب'), findsOneWidget);

    // The WhatsApp glyph appears once, for the checkbox label (4c), and not yet as a target.
    expect(find.byIcon(Icons.chat_outlined), findsOneWidget);

    await tester.tap(find.widgetWithText(CheckboxListTile, 'واتساب'));
    await tester.pump();

    // Now it is a verification target too: label + target = two.
    expect(find.byIcon(Icons.chat_outlined), findsNWidgets(2));
    expect(find.textContaining('سيتم إرسال رموز التحقق لاعتماد:'), findsOneWidget);
    expect(find.textContaining('لن يتم حفظ'), findsNothing);
  });

  testWidgets(
    'Next stays disabled with a phone channel selected but no phone number entered (F3, S5-02)',
    (tester) async {
      await pump(tester);

      final button = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(button.onPressed, isNull); // both channels default-selected, phone still empty

      await tester.enterText(find.byType(TextField).first, '0912345678');
      await tester.pump();

      expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNotNull);
    },
  );

  testWidgets(
    'the email row offers its own deselect once an address is entered, and deselecting it '
    'clears the address from the consequence text without erasing the typed text (F8, S5-02)',
    (tester) async {
      await pump(tester);

      expect(find.widgetWithText(CheckboxListTile, 'حفظ البريد الإلكتروني كوسيلة تواصل'), findsNothing);

      await tester.enterText(find.byType(TextField).at(1), 'a@example.com');
      await tester.pump();

      final emailCheckbox = find.widgetWithText(
        CheckboxListTile,
        'حفظ البريد الإلكتروني كوسيلة تواصل',
      );
      expect(emailCheckbox, findsOneWidget);
      expect(find.textContaining('البريد الإلكتروني'), findsWidgets); // now listed as saved

      // See the note on the other email-checkbox tap: the progress strip pushed this row under
      // the «التالي» button, so the tap needs the row scrolled into view first.
      await tester.ensureVisible(emailCheckbox);
      await tester.pumpAndSettle();
      await tester.tap(emailCheckbox);
      await tester.pump();

      // BL-086: WhatsApp is unsaved by default now, so the not-saved clause lists BOTH channels
      // (joined with « و» by `_consequenceText`), not the email alone.
      expect(find.textContaining('لن يتم حفظ واتساب والبريد الإلكتروني'), findsOneWidget);
      expect(find.text('a@example.com'), findsOneWidget); // the typed address survives
    },
  );

  testWidgets(
    'a deselected email address is genuinely never submitted, not merely hidden from the UI '
    '(G3, S5-02 second pass)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.contactChannelsResultToServe = const ContactChannelsResult(profileId: 'p1', channels: []);

      await tester.enterText(find.byType(TextField).at(1), 'a@example.com');
      await tester.pump();
      // `ensureVisible` since the journey progress strip (walk comments 1/5, 2026-09-10) added
      // 44 dp to the top of this screen: without it the tap lands on «التالي» sitting over the
      // checkbox, which is a silent wrong-widget tap rather than a failure.
      final emailCheckbox = find.widgetWithText(
        CheckboxListTile,
        'حفظ البريد الإلكتروني كوسيلة تواصل',
      );
      await tester.ensureVisible(emailCheckbox);
      await tester.pumpAndSettle();
      await tester.tap(emailCheckbox);
      await tester.pump();
      await tester.enterText(find.byType(TextField).first, '0912345678');
      await tester.pump();
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(landedPath(), '/channel-verification');
      expect(api.lastSubmittedEmailAddress, isNull);
      final progress = await (sessionDb.select(sessionDb.localProgress)).getSingle();
      expect(progress.resumeStage, 'awaitingVerification');
    },
  );

  testWidgets(
    'typing a fresh address after deselecting reactivates the checkbox and submits it '
    '(G5, S5-02 second pass)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.contactChannelsResultToServe = const ContactChannelsResult(profileId: 'p1', channels: []);

      await tester.enterText(find.byType(TextField).at(1), 'a@example.com');
      await tester.pump();
      // `ensureVisible` since the journey progress strip (walk comments 1/5, 2026-09-10) added
      // 44 dp to the top of this screen: without it the tap lands on «التالي» sitting over the
      // checkbox, which is a silent wrong-widget tap rather than a failure.
      final emailCheckbox = find.widgetWithText(
        CheckboxListTile,
        'حفظ البريد الإلكتروني كوسيلة تواصل',
      );
      await tester.ensureVisible(emailCheckbox);
      await tester.pumpAndSettle();
      await tester.tap(emailCheckbox);
      await tester.pump();
      // Clear, then type a genuinely new address — this is the "activation" customer.md means.
      await tester.enterText(find.byType(TextField).at(1), '');
      await tester.pump();
      await tester.enterText(find.byType(TextField).at(1), 'b@example.com');
      await tester.pump();
      await tester.enterText(find.byType(TextField).first, '0912345678');
      await tester.pump();
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(landedPath(), '/channel-verification');
      expect(api.lastSubmittedEmailAddress, 'b@example.com');
      final draft = await (sessionDb.select(sessionDb.localDraft)).getSingle();
      expect(draft.emailSelected, isTrue); // persisted, not just in-memory UI state
    },
  );

  testWidgets(
    'submits the pair Stage 1a actually verified, not a since-edited LocalDraft (G4, S5-02 '
    'second pass)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.contactChannelsResultToServe = const ContactChannelsResult(profileId: 'p1', channels: []);

      // Simulates the customer going back and editing the draft's branch/account without
      // re-running Stage 1a — the pumpWithRouter helper seeded LocalProgress's verified pair as
      // '2'/'12345'; this writes a DIFFERENT pair into LocalDraft on the SAME database instance.
      await sessionDb
          .into(sessionDb.localDraft)
          .insertOnConflictUpdate(
            LocalDraftCompanion.insert(
              id: const Value(0),
              branchCode: const Value('99'),
              accountNumber: const Value('00000'),
              updatedAt: DateTime.now(),
            ),
          );

      await tester.enterText(find.byType(TextField).first, '0912345678');
      await tester.pump();
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(landedPath(), '/channel-verification');
      expect(api.lastSubmittedBranch, '2'); // the VERIFIED pair, never the edited draft's '99'
      expect(api.lastSubmittedAccountNumber, '12345');
    },
  );

  testWidgets('an abandon action is available directly from Stage 1b (F9, S5-02)', (
    tester,
  ) async {
    final landedPath = (await pumpWithRouter(tester)).landedPath;

    await tester.tap(find.text('التراجع عن الجلسة'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/account-entry');
  });

  testWidgets('Arabic-Indic digits typed into the phone field render as ASCII', (tester) async {
    await pump(tester);

    await tester.enterText(find.byType(TextField).first, '٠٩١٢٣٤٥٦٧٨');
    await tester.pump();

    expect(find.text('0912345678'), findsOneWidget);
  });

  testWidgets('offline: true renders the offline banner and disables Next', (tester) async {
    await pump(tester, offline: true);

    expect(find.textContaining('لا يوجد اتصال بالإنترنت'), findsOneWidget);
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull);
  });

  testWidgets('a successful submit navigates to /channel-verification', (tester) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.contactChannelsResultToServe = const ContactChannelsResult(
      profileId: 'p1',
      channels: [
        ChannelSummary(
          channel: 'sms',
          state: ChannelState.unverified,
          maskedDestination: '••••1234',
        ),
      ],
    );

    await tester.enterText(find.byType(TextField).first, '0912345678');
    await tester.pump();
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), '/channel-verification');
    final progress = await (sessionDb.select(sessionDb.localProgress)).getSingle();
    expect(progress.resumeStage, 'awaitingVerification');
  });

  testWidgets(
    'ProfileAlreadyCompleteException (409) navigates to /ended and clears local state '
    '(F6, S5-02, BL-123)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.contactChannelsErrorToThrow = const ProfileAlreadyCompleteException();

      await tester.enterText(find.byType(TextField).first, '0912345678');
      await tester.pump();
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(landedPath(), '/ended');
      expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNull);
    },
  );

  testWidgets('SessionTemporarilyBlockedException (429) shows an inline message, no navigation', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.contactChannelsErrorToThrow = const SessionTemporarilyBlockedException();

    await tester.enterText(find.byType(TextField).first, '0912345678');
    await tester.pump();
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(find.textContaining('متوقف مؤقتًا'), findsOneWidget);
    // Rejected, not resumed — local state is untouched, not cleared (this is a retry-later
    // outcome, not a terminal one).
    expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNotNull);
  });

  testWidgets('a backend-unreachable submit shows an inline retry message, no navigation', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.contactChannelsErrorToThrow = const BackendUnreachableException('down');

    await tester.enterText(find.byType(TextField).first, '0912345678');
    await tester.pump();
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(find.textContaining('تعذر الاتصال'), findsOneWidget);
    expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNotNull);
  });

  testWidgets('an unmapped failure (e.g. a 5xx) shows a generic message, not silence (F4, S5-02)', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.contactChannelsErrorToThrow = StateError('unexpected 500');

    await tester.enterText(find.byType(TextField).first, '0912345678');
    await tester.pump();
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(find.textContaining('حدث خطأ غير متوقع'), findsOneWidget);
    // The failed submit never advanced LocalProgress past the pre-seeded contactChannels stage.
    final progress = await (sessionDb.select(sessionDb.localProgress)).getSingle();
    expect(progress.resumeStage, 'contactChannels');
  });
}
