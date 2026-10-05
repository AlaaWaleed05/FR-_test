import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/entry/entry_repository.dart';

import 'fake_entry_api.dart';

void main() {
  late FakeEntryApi api;
  late SessionDatabase db;
  late EntryRepository repository;

  setUp(() {
    api = FakeEntryApi();
    db = SessionDatabase.forTesting();
    repository = EntryRepository(api: api, db: db);
  });

  tearDown(() async {
    await db.close();
  });

  group('verifiedAccount', () {
    test(
      'returns null when no account has ever passed account-check',
      () async {
        expect(await repository.verifiedAccount(), isNull);
      },
    );

    test(
      'returns the verified pair, even after LocalDraft is edited to a different account '
      '(F5 regression, S5-02: Stage 1b must never submit against an unrevalidated draft)',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');

        // Simulates the customer going back and editing the draft without re-running 1a.
        await repository.saveDraft(
          const EntryDraft(branchCode: '3', accountNumber: '99999'),
        );

        final verified = await repository.verifiedAccount();
        expect(verified?.branchCode, '2');
        expect(verified?.accountNumber, '12345');
      },
    );
  });

  group('checkAccount', () {
    test(
      'PROCEED writes LocalProgress so the account becomes an in-progress session',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );

        await repository.checkAccount('2', '12345');

        final progress = await (db.select(
          db.localProgress,
        )..where((t) => t.id.equals(0))).getSingleOrNull();
        expect(progress, isNotNull);
        expect(progress!.verifiedBranchCode, '2');
        expect(progress.verifiedAccountNumber, '12345');
        expect(progress.resumeStage, 'contactChannels');
      },
    );

    test('RETRY (invalid account) writes no LocalProgress', () async {
      api.accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.invalid,
        continuation: AccountContinuation.retry,
        requestId: 'r1',
      );

      await repository.checkAccount('2', 'bad');

      expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
    });

    test('TERMINAL (inactive) writes no LocalProgress', () async {
      api.accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.inactive,
        continuation: AccountContinuation.terminal,
        requestId: 'r1',
      );

      await repository.checkAccount('2', '12345');

      expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
    });
  });

  group('submitContactChannels', () {
    test(
      'success advances LocalProgress to awaitingVerification with a profile id and channels',
      () async {
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

        await repository.submitContactChannels(
          branch: '2',
          accountNumber: '12345',
          phoneNumber: '+249912345678',
          sms: true,
          whatsapp: false,
        );

        final progress = await (db.select(
          db.localProgress,
        )..where((t) => t.id.equals(0))).getSingleOrNull();
        expect(progress, isNotNull);
        expect(progress!.resumeStage, 'awaitingVerification');
        expect(progress.profileId, 'p1');
        expect(
          ChannelSummary.decodeList(
            progress.channelsSummary,
          ).single.maskedDestination,
          '••••1234',
        );
      },
    );

    test(
      'propagates ContactChannelsRejectedException without writing LocalProgress',
      () async {
        api.contactChannelsErrorToThrow =
            const ContactChannelsRejectedException();

        await expectLater(
          () => repository.submitContactChannels(
            branch: '2',
            accountNumber: '12345',
            phoneNumber: '+249912345678',
            sms: false,
            whatsapp: false,
          ),
          throwsA(isA<ContactChannelsRejectedException>()),
        );
        expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
      },
    );

    test('propagates ProfileAlreadyCompleteException', () async {
      api.contactChannelsErrorToThrow = const ProfileAlreadyCompleteException();

      await expectLater(
        () => repository.submitContactChannels(
          branch: '2',
          accountNumber: '12345',
          phoneNumber: '+249912345678',
          sms: true,
          whatsapp: true,
        ),
        throwsA(isA<ProfileAlreadyCompleteException>()),
      );
    });

    test('propagates BackendUnreachableException', () async {
      api.contactChannelsErrorToThrow = const BackendUnreachableException(
        'down',
      );

      await expectLater(
        () => repository.submitContactChannels(
          branch: '2',
          accountNumber: '12345',
          phoneNumber: '+249912345678',
          sms: true,
          whatsapp: true,
        ),
        throwsA(isA<BackendUnreachableException>()),
      );
    });
  });

  /// Seeds `LocalProgress` at `awaitingVerification` with two channels, as `submitContactChannels`
  /// would have left it — the state every Stage 2 repository method assumes on entry.
  Future<void> seedAwaitingVerification() async {
    api.contactChannelsResultToServe = const ContactChannelsResult(
      profileId: 'p1',
      channels: [
        ChannelSummary(
          channel: 'sms',
          state: ChannelState.unverified,
          maskedDestination: '••1',
        ),
        ChannelSummary(
          channel: 'whatsapp',
          state: ChannelState.unverified,
          maskedDestination: '••1',
        ),
      ],
    );
    await repository.submitContactChannels(
      branch: '2',
      accountNumber: '12345',
      phoneNumber: '+249912345678',
      sms: true,
      whatsapp: true,
    );
  }

  group('pendingVerificationChannels', () {
    test(
      'filters out declined channels, keeps unverified/verified ones',
      () async {
        api.contactChannelsResultToServe = const ContactChannelsResult(
          profileId: 'p1',
          channels: [
            ChannelSummary(
              channel: 'sms',
              state: ChannelState.unverified,
              maskedDestination: '••1',
            ),
            ChannelSummary(
              channel: 'whatsapp',
              state: ChannelState.declined,
              maskedDestination: '••1',
            ),
          ],
        );
        await repository.submitContactChannels(
          branch: '2',
          accountNumber: '12345',
          phoneNumber: '+249912345678',
          sms: true,
          whatsapp: false,
        );

        final channels = await repository.pendingVerificationChannels();
        expect(channels.map((c) => c.channel), ['sms']);
      },
    );

    test('returns an empty list when no LocalProgress exists', () async {
      expect(await repository.pendingVerificationChannels(), isEmpty);
    });
  });

  group('verifyChannel', () {
    test('VERIFIED write-throughs the cached channelsSummary', () async {
      await seedAwaitingVerification();
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );

      final result = await repository.verifyChannel(
        channel: 'sms',
        code: '123456',
      );

      expect(result.outcome, OtpVerifyOutcome.verified);
      expect(api.lastVerifiedProfileId, 'p1');
      expect(api.lastVerifiedChannel, 'sms');
      expect(api.lastVerifiedCode, '123456');
      final channels = await repository.pendingVerificationChannels();
      expect(
        channels.firstWhere((c) => c.channel == 'sms').state,
        ChannelState.verified,
      );
      // The untouched channel keeps its own cached state.
      expect(
        channels.firstWhere((c) => c.channel == 'whatsapp').state,
        ChannelState.unverified,
      );
    });

    test('WRONG_CODE leaves the cached channelsSummary untouched', () async {
      await seedAwaitingVerification();
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.wrongCode,
        state: ChannelState.unverified,
        sessionBlockedUntil: null,
      );

      await repository.verifyChannel(channel: 'sms', code: '000000');

      final channels = await repository.pendingVerificationChannels();
      expect(
        channels.firstWhere((c) => c.channel == 'sms').state,
        ChannelState.unverified,
      );
    });

    test('propagates a session block from CHANNEL_LOCKED', () async {
      await seedAwaitingVerification();
      final blockedUntil = DateTime.utc(2026, 9, 1, 12, 30);
      api.verifyChannelResultToServe = OtpVerifyResult(
        channel: 'whatsapp',
        outcome: OtpVerifyOutcome.channelLocked,
        state: ChannelState.unverified,
        sessionBlockedUntil: blockedUntil,
      );

      final result = await repository.verifyChannel(
        channel: 'whatsapp',
        code: '000000',
      );

      expect(result.sessionBlockedUntil, blockedUntil);
    });

    test('two channels verifying concurrently both survive in the cache — regression for a '
        'lost-update race found under review, S5-04', () async {
      await seedAwaitingVerification();
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );

      // Two rows can genuinely verify at once (the screen's submit guard is per-row); without
      // `_markChannelVerified`'s transaction wrapping, the second write's stale read-then-write
      // clobbers the first channel's `verified` state.
      await Future.wait([
        repository.verifyChannel(channel: 'sms', code: '111111'),
        repository.verifyChannel(channel: 'whatsapp', code: '222222'),
      ]);

      final channels = await repository.pendingVerificationChannels();
      expect(
        channels.firstWhere((c) => c.channel == 'sms').state,
        ChannelState.verified,
      );
      expect(
        channels.firstWhere((c) => c.channel == 'whatsapp').state,
        ChannelState.verified,
      );
    });
  });

  group('resendChannel', () {
    test(
      'ALREADY_VERIFIED write-throughs the cache — self-correcting a stale resume',
      () async {
        await seedAwaitingVerification();
        api.resendChannelResultToServe = const OtpResendResult(
          channel: 'sms',
          outcome: OtpResendOutcome.alreadyVerified,
          maskedDestination: '••1',
          secondsUntilAllowed: null,
        );

        await repository.resendChannel(channel: 'sms');

        final channels = await repository.pendingVerificationChannels();
        expect(
          channels.firstWhere((c) => c.channel == 'sms').state,
          ChannelState.verified,
        );
      },
    );

    test(
      'TOO_SOON leaves the cache untouched and returns the real countdown',
      () async {
        await seedAwaitingVerification();
        api.resendChannelResultToServe = const OtpResendResult(
          channel: 'sms',
          outcome: OtpResendOutcome.tooSoon,
          maskedDestination: '••1',
          secondsUntilAllowed: 45,
        );

        final result = await repository.resendChannel(channel: 'sms');

        expect(result.secondsUntilAllowed, 45);
        final channels = await repository.pendingVerificationChannels();
        expect(
          channels.firstWhere((c) => c.channel == 'sms').state,
          ChannelState.unverified,
        );
      },
    );

    // ----- BL-101, the corrected-email path -----

    /// Seeds a session whose selected channels include email, which
    /// [seedAwaitingVerification] deliberately does not.
    Future<void> seedWithEmail() async {
      api.contactChannelsResultToServe = const ContactChannelsResult(
        profileId: 'p1',
        channels: [
          ChannelSummary(
            channel: 'sms',
            state: ChannelState.unverified,
            maskedDestination: '••1',
          ),
          ChannelSummary(
            channel: 'email',
            state: ChannelState.unverified,
            maskedDestination: 'a•••@x.com',
          ),
        ],
      );
      await repository.submitContactChannels(
        branch: '2',
        accountNumber: '12345',
        phoneNumber: '+249912345678',
        sms: true,
        whatsapp: false,
        emailAddress: 'a@x.com',
      );
    }

    test('an ordinary resend forwards no corrected address', () async {
      await seedAwaitingVerification();
      api.resendChannelResultToServe = const OtpResendResult(
        channel: 'sms',
        outcome: OtpResendOutcome.issued,
        maskedDestination: '••1',
        secondsUntilAllowed: null,
      );

      await repository.resendChannel(channel: 'sms');

      expect(api.lastResendedCorrectedEmail, isNull);
    });

    test(
      'a corrected address write-throughs the new mask, so a resume shows the corrected '
      'address rather than the typo',
      () async {
        await seedWithEmail();
        api.resendChannelResultToServe = const OtpResendResult(
          channel: 'email',
          outcome: OtpResendOutcome.issued,
          maskedDestination: 'n•••@y.com',
          secondsUntilAllowed: null,
        );

        await repository.resendChannel(
          channel: 'email',
          correctedEmailAddress: 'nour@y.com',
        );

        expect(api.lastResendedCorrectedEmail, 'nour@y.com');
        final channels = await repository.pendingVerificationChannels();
        expect(
          channels.firstWhere((c) => c.channel == 'email').maskedDestination,
          'n•••@y.com',
        );
        // The other row is untouched — correcting the email must not disturb phone state.
        expect(
          channels.firstWhere((c) => c.channel == 'sms').maskedDestination,
          '••1',
        );
      },
    );

    test(
      'the mask write-through still lands when the resend itself is REFUSED — the backend '
      'applies the correction before it decides the reservation outcome',
      () async {
        await seedWithEmail();
        api.resendChannelResultToServe = const OtpResendResult(
          channel: 'email',
          outcome: OtpResendOutcome.tooSoon,
          maskedDestination: 'n•••@y.com',
          secondsUntilAllowed: 28,
        );

        await repository.resendChannel(
          channel: 'email',
          correctedEmailAddress: 'nour@y.com',
        );

        final channels = await repository.pendingVerificationChannels();
        expect(
          channels.firstWhere((c) => c.channel == 'email').maskedDestination,
          'n•••@y.com',
        );
      },
    );

    test(
      'ALREADY_VERIFIED writes NEITHER value through — the backend skips the correction for a '
      'verified channel, so storing it would leave the app holding an address the backend '
      'explicitly refused (regression, @agent-reviewer S8-10)',
      () async {
        await seedWithEmail();
        await repository.saveDraft(
          const EntryDraft(phoneNumber: '+249912345678', emailAddress: 'a@x.com'),
        );
        api.resendChannelResultToServe = const OtpResendResult(
          channel: 'email',
          outcome: OtpResendOutcome.alreadyVerified,
          maskedDestination: 'a•••@x.com',
          secondsUntilAllowed: null,
        );

        await repository.resendChannel(
          channel: 'email',
          correctedEmailAddress: 'nour@y.com',
        );

        expect((await repository.loadDraft()).emailAddress, 'a@x.com');
        final channels = await repository.pendingVerificationChannels();
        expect(
          channels.firstWhere((c) => c.channel == 'email').maskedDestination,
          'a•••@x.com',
        );
      },
    );

    test(
      'a correction concurrent with a phone verify does not clobber it — the whole '
      'channelsSummary is one string, so both write-throughs are whole-list read-modify-writes. '
      'Proven by reverting both transactions, which makes this fail. Reverting only the one in '
      '_updateChannelMask does NOT fail it under this interleaving, so what this pins is that '
      'the PAIR must be transactional; see that method doc for the ordering its own guard '
      'covers, which this test does not reach',
      () async {
        await seedWithEmail();
        api.verifyChannelResultToServe = const OtpVerifyResult(
          channel: 'sms',
          outcome: OtpVerifyOutcome.verified,
          state: ChannelState.verified,
          sessionBlockedUntil: null,
        );
        api.resendChannelResultToServe = const OtpResendResult(
          channel: 'email',
          outcome: OtpResendOutcome.issued,
          maskedDestination: 'n•••@y.com',
          secondsUntilAllowed: null,
        );

        await Future.wait([
          repository.verifyChannel(channel: 'sms', code: '123456'),
          repository.resendChannel(channel: 'email', correctedEmailAddress: 'nour@y.com'),
        ]);

        final channels = await repository.pendingVerificationChannels();
        // Neither write may have overwritten the other with its own stale snapshot.
        expect(
          channels.firstWhere((c) => c.channel == 'sms').state,
          ChannelState.verified,
        );
        expect(
          channels.firstWhere((c) => c.channel == 'email').maskedDestination,
          'n•••@y.com',
        );
      },
    );

    test(
      'a corrected address is saved into the draft, so a SECOND correction prefills what the '
      'customer last entered rather than the mistake they already fixed',
      () async {
        await seedWithEmail();
        // The draft is written by `ContactChannelsScreen`'s autosave as the customer types, not by
        // `submitContactChannels` (which touches only `LocalProgress`) — seeded here to match what
        // Stage 1b actually leaves behind, since that is what the correction editor prefills from.
        await repository.saveDraft(
          const EntryDraft(
            branchCode: '2',
            accountNumber: '12345',
            phoneNumber: '+249912345678',
            emailAddress: 'a@x.com',
          ),
        );
        expect((await repository.loadDraft()).emailAddress, 'a@x.com');

        api.resendChannelResultToServe = const OtpResendResult(
          channel: 'email',
          outcome: OtpResendOutcome.issued,
          maskedDestination: 'n•••@y.com',
          secondsUntilAllowed: null,
        );
        await repository.resendChannel(
          channel: 'email',
          correctedEmailAddress: 'nour@y.com',
        );

        expect((await repository.loadDraft()).emailAddress, 'nour@y.com');
        // Nothing else in the draft was clobbered by the merge.
        expect((await repository.loadDraft()).phoneNumber, '+249912345678');
      },
    );
  });

  group('completeVerification', () {
    test(
      'advances resumeStage straight to stage3 without touching the cached channels '
      '(S5-05: no more intermediate "verified" resting state)',
      () async {
        await seedAwaitingVerification();

        await repository.completeVerification();

        final progress = await (db.select(
          db.localProgress,
        )..where((t) => t.id.equals(0))).getSingle();
        expect(progress.resumeStage, 'stage3');
        expect(progress.profileId, 'p1');
        expect(
          ChannelSummary.decodeList(progress.channelsSummary),
          hasLength(2),
        );
      },
    );
  });

  group('saveDraft / loadDraft', () {
    test('round-trips every field', () async {
      await repository.saveDraft(
        const EntryDraft(
          branchCode: '2',
          accountNumber: '12345',
          phoneNumber: '0912345678',
          smsSelected: true,
          whatsappSelected: false,
          emailAddress: 'a@example.com',
        ),
      );

      final draft = await repository.loadDraft();
      expect(draft.branchCode, '2');
      expect(draft.accountNumber, '12345');
      expect(draft.phoneNumber, '0912345678');
      expect(draft.smsSelected, isTrue);
      expect(draft.whatsappSelected, isFalse);
      expect(draft.emailAddress, 'a@example.com');
    });

    test(
      'a later save with copyWith does not clobber fields it did not touch',
      () async {
        await repository.saveDraft(
          const EntryDraft(
            phoneNumber: '0912345678',
            smsSelected: false,
            emailAddress: 'a@b.com',
          ),
        );

        final current = await repository.loadDraft();
        await repository.saveDraft(
          current.copyWith(branchCode: '2', accountNumber: '999'),
        );

        final draft = await repository.loadDraft();
        expect(draft.branchCode, '2');
        expect(draft.accountNumber, '999');
        expect(draft.phoneNumber, '0912345678');
        expect(draft.smsSelected, isFalse);
        expect(draft.emailAddress, 'a@b.com');
      },
    );
  });

  group('launchDecision — BL-021, the phone lock', () {
    /// Puts a real in-progress session on disk, so `launchDecision` reaches the backend call at
    /// all, then arms the API with a live `BLOCKED`.
    Future<DateTime> armBlockedSession() async {
      api.accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.active,
        continuation: AccountContinuation.proceed,
        requestId: 'r1',
      );
      await repository.checkAccount('2', '12345');

      final until = DateTime.now().add(const Duration(minutes: 15));
      api.accountCheckResultToServe = AccountCheckResult(
        outcome: AccountOutcome.active,
        continuation: AccountContinuation.blocked,
        requestId: 'r2',
        blockedUntil: until,
      );
      return until;
    }

    test(
      'BLOCKED returns LaunchBlocked carrying the deadline, instead of throwing ArgumentError',
      () async {
        // The whole of BL-021 in one assertion. `AccountContinuation` had no `blocked` value, so
        // `values.byName('blocked')` threw out of the decode and the customer was told to check
        // their internet connection — for a bank-applied lock that no retry could clear.
        final until = await armBlockedSession();

        final decision = await repository.launchDecision();

        expect(decision, isA<LaunchBlocked>());
        expect((decision as LaunchBlocked).blockedUntil, until);
      },
    );

    test(
      'BLOCKED does NOT clear local state — the block costs a wait, never the customer\'s draft',
      () async {
        // The distinction from LaunchTerminal, and it is the reason "LOSES DATA" was the wrong
        // harm class for this defect: nothing on this path is destroyed, so a customer who waits
        // out the lock resumes exactly where they were.
        await armBlockedSession();

        await repository.launchDecision();

        expect(await repository.verifiedAccount(), isNotNull);
      },
    );

    test(
      'a lock that has lapsed simply resumes — the backend stops sending BLOCKED and nothing '
      'special is needed on the client',
      () async {
        // Proves the self-healing property the corrected BL-021 row claims: the backend only
        // sends BLOCKED while the lock is in the future, so recovery is just the next launch.
        await armBlockedSession();
        expect(await repository.launchDecision(), isA<LaunchBlocked>());

        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r3',
        );

        expect(await repository.launchDecision(), isA<ResumeContactChannels>());
      },
    );
  });

  group('launchDecision', () {
    test(
      'no LocalProgress and no LocalDraft returns a bare FreshStart, with no backend call',
      () async {
        final decision = await repository.launchDecision();
        expect(decision, isA<FreshStart>());
        expect(api.checkAccountCallCount, 0);
      },
    );

    test(
      'a bare unsubmitted 1a draft (no LocalProgress) prefills FreshStart with no backend call',
      () async {
        await repository.saveDraft(
          const EntryDraft(branchCode: '2', accountNumber: '12345'),
        );

        final decision = await repository.launchDecision();

        expect(decision, isA<FreshStart>());
        final fresh = decision as FreshStart;
        expect(fresh.draftBranchCode, '2');
        expect(fresh.draftAccountNumber, '12345');
        expect(api.checkAccountCallCount, 0);
      },
    );

    test(
      'LocalProgress at contactChannels + backend PROCEED resumes online at contact-channels',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');

        final decision = await repository.launchDecision();

        expect(decision, isA<ResumeContactChannels>());
        final resume = decision as ResumeContactChannels;
        expect(resume.branchCode, '2');
        expect(resume.accountNumber, '12345');
        expect(resume.offline, isFalse);
      },
    );

    test(
      'LocalProgress at awaitingVerification + backend PROCEED resumes online there',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        api.contactChannelsResultToServe = const ContactChannelsResult(
          profileId: 'p1',
          channels: [
            ChannelSummary(
              channel: 'sms',
              state: ChannelState.unverified,
              maskedDestination: '••1',
            ),
          ],
        );
        await repository.submitContactChannels(
          branch: '2',
          accountNumber: '12345',
          phoneNumber: '+249912345678',
          sms: true,
          whatsapp: true,
        );

        final decision = await repository.launchDecision();

        expect(decision, isA<ResumeAwaitingVerification>());
        final resume = decision as ResumeAwaitingVerification;
        expect(resume.profileId, 'p1');
        expect(resume.channels.single.maskedDestination, '••1');
        expect(resume.offline, isFalse);
      },
    );

    test(
      'LocalProgress at stage3 (S5-05: completeVerification writes this directly, no more '
      '"verified" resting state) + backend PROCEED resumes online on the data-entry stage',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        await seedAwaitingVerification();
        api.verifyChannelResultToServe = const OtpVerifyResult(
          channel: 'sms',
          outcome: OtpVerifyOutcome.verified,
          state: ChannelState.verified,
          sessionBlockedUntil: null,
        );
        await repository.verifyChannel(channel: 'sms', code: '123456');
        await repository.completeVerification();

        final decision = await repository.launchDecision();

        expect(decision, isA<ResumeDataEntry>());
        final resume = decision as ResumeDataEntry;
        expect(resume.stage, DataEntryStage.stage3);
        expect(resume.offline, isFalse);
      },
    );

    test('LocalProgress at beyondStage9 (Stage 9 accepted) + backend PROCEED resumes into the FINAL '
        'STAGES gate, which asks the backend which of 10/11/12 — not the old post-stage-9 '
        'placeholder, and not a locally-guessed stage', () async {
      api.accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.active,
        continuation: AccountContinuation.proceed,
        requestId: 'r1',
      );
      await repository.checkAccount('2', '12345');
      await seedAwaitingVerification();
      api.verifyChannelResultToServe = const OtpVerifyResult(
        channel: 'sms',
        outcome: OtpVerifyOutcome.verified,
        state: ChannelState.verified,
        sessionBlockedUntil: null,
      );
      await repository.verifyChannel(channel: 'sms', code: '123456');
      // Simulates Stage 9's acceptance (owned by `IdentityScanRepository.advanceToStage`, a
      // different repository not exercised in this file). S5-08 repurposed what this value means:
      // it was "past everything this app version builds" and is now "somewhere in stages 10-12,
      // ask the backend which".
      await (db.update(db.localProgress)..where((t) => t.id.equals(0))).write(
        const LocalProgressCompanion(
          resumeStage: Value(resumeStageBeyondStage9),
        ),
      );

      final decision = await repository.launchDecision();

      // Deliberately carries NO stage and no channel snapshot. Stages 10-12's real state is
      // backend-owned, so this decision names a question rather than a screen — see
      // `FinalStagesGateScreen`, which performs the single `POST /submission/current` read that
      // answers it. A cached channel list here would be a second source for the confirmation
      // screen's channels, which is exactly the BL-058 bug S5-13 removed.
      expect(decision, isA<ResumeFinalStages>());
      expect((decision as ResumeFinalStages).offline, isFalse);
    });

    test(
      'LocalProgress at the now-LEGACY "beyondStage6" value (an S5-05/S5-06-build device that '
      'completed Stage 6 and never relaunched since) resumes onto Stage 7, not onto the '
      'post-stage-9 placeholder — the same free-text-value migration the "verified" case below '
      'documents, one generation later',
      () async {
        // Without this mapping the customer lands past the end of the journey and can never reach
        // the identity scan at all: 'beyondStage6' meant "past everything built" when nothing
        // beyond Stage 6 existed, and now means exactly "Stage 6 done, Stage 7 next".
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        await seedAwaitingVerification();
        api.verifyChannelResultToServe = const OtpVerifyResult(
          channel: 'sms',
          outcome: OtpVerifyOutcome.verified,
          state: ChannelState.verified,
          sessionBlockedUntil: null,
        );
        await repository.verifyChannel(channel: 'sms', code: '123456');
        await (db.update(db.localProgress)..where((t) => t.id.equals(0))).write(
          const LocalProgressCompanion(resumeStage: Value('beyondStage6')),
        );

        final decision = await repository.launchDecision();

        expect(decision, isA<ResumeDataEntry>());
        expect((decision as ResumeDataEntry).stage, DataEntryStage.stage7);
      },
    );

    for (final stage in IdentityScanStage.values) {
      test(
        'LocalProgress at ${stage.name} resumes onto the identity-scan stage of that name',
        () async {
          api.accountCheckResultToServe = const AccountCheckResult(
            outcome: AccountOutcome.active,
            continuation: AccountContinuation.proceed,
            requestId: 'r1',
          );
          await repository.checkAccount('2', '12345');
          await seedAwaitingVerification();
          api.verifyChannelResultToServe = const OtpVerifyResult(
            channel: 'sms',
            outcome: OtpVerifyOutcome.verified,
            state: ChannelState.verified,
            sessionBlockedUntil: null,
          );
          await repository.verifyChannel(channel: 'sms', code: '123456');
          await (db.update(db.localProgress)..where((t) => t.id.equals(0)))
              .write(LocalProgressCompanion(resumeStage: Value(stage.name)));

          final decision = await repository.launchDecision();

          expect(decision, isA<ResumeIdentityScan>());
          expect((decision as ResumeIdentityScan).stage, stage);
        },
      );
    }

    test(
      'LocalProgress at the RETIRED "verified" value (an S5-04-build device that never '
      'relaunched since) resumes onto stage 3, not back to contact-channel selection (S5-05 '
      'review, second pass: this free-text-value migration was missed even though the SCHEMA '
      'migration for the new tables was handled)',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        await seedAwaitingVerification();
        await (db.update(db.localProgress)..where((t) => t.id.equals(0))).write(
          const LocalProgressCompanion(resumeStage: Value('verified')),
        );

        final decision = await repository.launchDecision();

        expect(decision, isA<ResumeDataEntry>());
        expect((decision as ResumeDataEntry).stage, DataEntryStage.stage3);
      },
    );

    test(
      'LocalProgress present but backend unreachable resumes offline, local state intact',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        api.accountCheckErrorToThrow = const BackendUnreachableException(
          'down',
        );

        final decision = await repository.launchDecision();

        expect(decision, isA<ResumeContactChannels>());
        expect((decision as ResumeContactChannels).offline, isTrue);
        // Local state must survive an unreachable backend (customer.md Stage 0).
        expect(
          await (db.select(db.localProgress)).getSingleOrNull(),
          isNotNull,
        );
      },
    );

    test(
      'backend TERMINAL (already complete) clears local state and returns LaunchEnded — BL-123',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.terminal,
          requestId: 'r2',
        );

        final decision = await repository.launchDecision();

        // BL-123: an already-complete profile is LaunchEnded, NOT LaunchTerminal. The two were
        // one type discriminated by a message string, and that string claimed the update had
        // been completed — false for a rejected, submitted or terminated profile alike.
        expect(decision, isA<LaunchEnded>());
        expect(decision, isNot(isA<LaunchTerminal>()));
        expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
      },
    );

    test(
      'backend TERMINAL also deletes a locally-captured salary-certificate file — a relaunch '
      'after finishing the journey is the LIKELY way a customer hits this, not an edge case '
      '(S5-05 review, second pass: the file-delete fix had only been wired into abandon())',
      () async {
        final tempDir = Directory.systemTemp.createTempSync(
          'fru_terminal_certificate_test_',
        );
        addTearDown(() {
          if (tempDir.existsSync()) tempDir.deleteSync(recursive: true);
        });
        final certificateFile = File('${tempDir.path}/salary_certificate.jpg')
          ..writeAsBytesSync([1, 2, 3]);
        await db
            .into(db.dataEntryDraft)
            .insertOnConflictUpdate(
              DataEntryDraftCompanion.insert(
                id: const Value(0),
                salaryCertificatePath: Value(certificateFile.path),
                updatedAt: DateTime.now(),
              ),
            );
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.terminal,
          requestId: 'r2',
        );

        await repository.launchDecision();

        expect(certificateFile.existsSync(), isFalse);
      },
    );

    test(
      'backend TERMINAL (inactive) uses distinct copy from an already-complete profile',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.inactive,
          continuation: AccountContinuation.terminal,
          requestId: 'r2',
        );

        final decision = await repository.launchDecision() as LaunchTerminal;

        expect(decision.message, contains('فرع'));
      },
    );

    test(
      'backend RETRY (account no longer found) clears local state, falls back to FreshStart',
      () async {
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.proceed,
          requestId: 'r1',
        );
        await repository.checkAccount('2', '12345');
        api.accountCheckResultToServe = const AccountCheckResult(
          outcome: AccountOutcome.invalid,
          continuation: AccountContinuation.retry,
          requestId: 'r2',
        );

        final decision = await repository.launchDecision();

        expect(decision, isA<FreshStart>());
        expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
      },
    );
  });

  group('abandon', () {
    test('clears LocalDraft and LocalProgress', () async {
      await repository.saveDraft(
        const EntryDraft(branchCode: '2', accountNumber: '12345'),
      );
      api.accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.active,
        continuation: AccountContinuation.proceed,
        requestId: 'r1',
      );
      await repository.checkAccount('2', '12345');

      await repository.abandon();

      expect(await (db.select(db.localDraft)).getSingleOrNull(), isNull);
      expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
    });

    test(
      'deletes a locally-captured salary-certificate file, not just its DataEntryDraft row '
      '(S5-05 review: the file lives outside the encrypted session.sqlite and was never '
      'otherwise removed)',
      () async {
        final tempDir = Directory.systemTemp.createTempSync(
          'fru_abandon_certificate_test_',
        );
        addTearDown(() {
          if (tempDir.existsSync()) tempDir.deleteSync(recursive: true);
        });
        final certificateFile = File('${tempDir.path}/salary_certificate.jpg')
          ..writeAsBytesSync([1, 2, 3]);
        await db
            .into(db.dataEntryDraft)
            .insertOnConflictUpdate(
              DataEntryDraftCompanion.insert(
                id: const Value(0),
                salaryCertificatePath: Value(certificateFile.path),
                updatedAt: DateTime.now(),
              ),
            );
        expect(certificateFile.existsSync(), isTrue);

        await repository.abandon();

        expect(certificateFile.existsSync(), isFalse);
        expect(await (db.select(db.dataEntryDraft)).getSingleOrNull(), isNull);
      },
    );

    test(
      'does nothing extra when no salary certificate was ever captured',
      () async {
        await repository
            .abandon(); // must not throw with no DataEntryDraft row at all
      },
    );
  });
}
