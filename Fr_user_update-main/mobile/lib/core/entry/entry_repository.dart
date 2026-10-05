import 'dart:io';

import 'package:drift/drift.dart';

import '../database/session_database.dart';
import 'entry_api.dart';
import 'entry_models.dart';

const _resumeStageContactChannels = 'contactChannels';
const _resumeStageAwaitingVerification = 'awaitingVerification';

/// Written by `ChannelVerificationScreen` once `DataEntryRepository.prepareCatalog` succeeds — the
/// real Stage 2→3 boundary (S5-05). `DataEntryStage.stage3.name` == `'stage3'`, so this is exactly
/// what `_resume`'s switch below decodes back via `DataEntryStage.values.byName(...)`.
final _resumeStageStage3 = DataEntryStage.stage3.name;

/// **Legacy since S5-07 — read, never written.** It was Stage 6's landing point while Stage 7
/// onward did not exist (S5-05); `Stage6Screen` now advances to `DataEntryStage.stage7.name`
/// instead. It stays decoded here because a device that completed Stage 6 under the previous build
/// still has this exact string on disk, and `_resume` maps it to Stage 7 — the same treatment the
/// retired `'verified'` value gets one case further down.
const _resumeStageBeyondStage6 = 'beyondStage6';

/// A save of Stage 1a/1b's customer-entered draft — read back by the entry screens to pre-fill
/// their fields on open/resume.
class EntryDraft {
  const EntryDraft({
    this.branchCode,
    this.accountNumber,
    this.phoneNumber,
    this.smsSelected = true,
    // BL-086 / pilot: WhatsApp is NOT selected by default. The pilot is SMS-only, and a tester who
    // left this ticked selected a channel that mints an OTP challenge nothing can deliver.
    //
    // **This is the only site that governs a fresh install**, which is not obvious: the Stage 1b
    // widget field and the Drift column default both look like the default and neither is. `_load`
    // overwrites the widget field from the loaded draft on every open, and `saveDraft` always
    // writes an explicit value, so the column default is unreachable outside tests. `loadDraft`
    // returning `const EntryDraft()` for "no row yet" is what a new customer actually gets.
    //
    // Narrower than D9.1's visible-but-disabled row, deliberately: the customer can still tick it.
    // That row needs R-042 (nothing tells the app which channels a deployment can verify) and
    // stays fenced; unticking a default needs no flag, so it is not the same change.
    this.whatsappSelected = false,
    this.emailAddress,
    this.emailSelected = true,
  });

  final String? branchCode;
  final String? accountNumber;
  final String? phoneNumber;
  final bool smsSelected;
  final bool whatsappSelected;
  final String? emailAddress;

  /// customer.md Stage 1b: the email row, once an address is entered, is independently
  /// deselectable — a deselect must not erase the typed address, so this is tracked separately
  /// from [emailAddress] itself (added under review, S5-02).
  final bool emailSelected;

  /// Merges in only the fields supplied, leaving the rest as-is — callers on a single screen
  /// (e.g. Stage 1a) must not clobber fields another screen (Stage 1b) already saved into the
  /// same singleton row.
  EntryDraft copyWith({
    String? branchCode,
    String? accountNumber,
    String? phoneNumber,
    bool? smsSelected,
    bool? whatsappSelected,
    Object? emailAddress = _unset,
    bool? emailSelected,
  }) {
    return EntryDraft(
      branchCode: branchCode ?? this.branchCode,
      accountNumber: accountNumber ?? this.accountNumber,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      smsSelected: smsSelected ?? this.smsSelected,
      whatsappSelected: whatsappSelected ?? this.whatsappSelected,
      emailAddress: identical(emailAddress, _unset)
          ? this.emailAddress
          : emailAddress as String?,
      emailSelected: emailSelected ?? this.emailSelected,
    );
  }
}

/// Sentinel distinguishing "not supplied" from "explicitly set to null" for
/// [EntryDraft.copyWith]'s [EntryDraft.emailAddress] parameter — an empty/cleared email field is
/// a real edit (clear the saved address), not "leave it alone".
const _unset = Object();

/// The testable core of journey Stage 0/1a/1b (docs/journeys/customer.md), depending only on
/// [EntryApi] + [SessionDatabase] — no dio/drift wiring details leak past this class, mirroring
/// `ReferenceRepository`'s own separation.
class EntryRepository {
  EntryRepository({required EntryApi api, required SessionDatabase db})
    : _api = api,
      _db = db;

  final EntryApi _api;
  final SessionDatabase _db;

  Future<EntryDraft> loadDraft() async {
    final row = await (_db.select(
      _db.localDraft,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    if (row == null) return const EntryDraft();
    return EntryDraft(
      branchCode: row.branchCode,
      accountNumber: row.accountNumber,
      phoneNumber: row.phoneNumber,
      smsSelected: row.smsSelected,
      whatsappSelected: row.whatsappSelected,
      emailAddress: row.emailAddress,
      emailSelected: row.emailSelected,
    );
  }

  /// Writes the whole draft row on every call — Stage 1a/1b fields are few and this is called
  /// once per customer edit, not once per keystroke across a large form, so a full replace stays
  /// simple and correct (no partial-update bookkeeping to get wrong).
  Future<void> saveDraft(EntryDraft draft) {
    return _db
        .into(_db.localDraft)
        .insertOnConflictUpdate(
          LocalDraftCompanion.insert(
            id: const Value(0),
            branchCode: Value(draft.branchCode),
            accountNumber: Value(draft.accountNumber),
            phoneNumber: Value(draft.phoneNumber),
            smsSelected: Value(draft.smsSelected),
            whatsappSelected: Value(draft.whatsappSelected),
            emailAddress: Value(draft.emailAddress),
            emailSelected: Value(draft.emailSelected),
            updatedAt: DateTime.now(),
          ),
        );
  }

  /// Stage 1a's action. On [AccountContinuation.proceed], records `LocalProgress` so this
  /// account/branch pair becomes an in-progress session Stage 0 can ask the backend about on a
  /// future launch — a bare unsubmitted draft never reaches this method.
  Future<AccountCheckResult> checkAccount(
    String branch,
    String accountNumber,
  ) async {
    final result = await _api.checkAccount(branch, accountNumber);
    if (result.continuation == AccountContinuation.proceed) {
      await _db
          .into(_db.localProgress)
          .insertOnConflictUpdate(
            LocalProgressCompanion.insert(
              id: const Value(0),
              verifiedBranchCode: branch,
              verifiedAccountNumber: accountNumber,
              resumeStage: _resumeStageContactChannels,
              updatedAt: DateTime.now(),
            ),
          );
    }
    return result;
  }

  /// Stage 1b's action. On success, advances `LocalProgress` to `awaitingVerification` and
  /// caches the channel summary for the resume placeholder to render.
  Future<ContactChannelsResult> submitContactChannels({
    required String branch,
    required String accountNumber,
    required String phoneNumber,
    required bool sms,
    required bool whatsapp,
    String? emailAddress,
  }) async {
    final result = await _api.submitContactChannels(
      branch: branch,
      accountNumber: accountNumber,
      phoneNumber: phoneNumber,
      sms: sms,
      whatsapp: whatsapp,
      emailAddress: emailAddress,
    );
    await _db
        .into(_db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: branch,
            verifiedAccountNumber: accountNumber,
            resumeStage: _resumeStageAwaitingVerification,
            profileId: Value(result.profileId),
            channelsSummary: Value(ChannelSummary.encodeList(result.channels)),
            updatedAt: DateTime.now(),
          ),
        );
    return result;
  }

  /// The exact branch/account pair that last passed `account-check` — what Stage 1b must submit
  /// against, never `LocalDraft`'s own branch/account fields. Found under review, S5-02: `Stage
  /// 1a itself never revalidates `LocalDraft` before Stage 1b reads it, so a customer who reaches
  /// a terminal screen, taps "back to start", edits the still-prefilled draft to a DIFFERENT
  /// account and kills the app before resubmitting 1a would otherwise leave a stale, never
  /// re-checked pair sitting in the draft for `ContactChannelsScreen` to submit — the backend has
  /// no way to detect this (`ContactChannelsRequest`'s own Javadoc: "nothing in this codebase yet
  /// ties a Stage 1b call back to a specific Stage 1a call"). Returns `null` only when no
  /// `LocalProgress` row exists, which `ContactChannelsScreen` should never actually hit — the
  /// screen is unreachable without one.
  Future<({String branchCode, String accountNumber})?> verifiedAccount() async {
    final progress = await (_db.select(
      _db.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    if (progress == null) return null;
    return (
      branchCode: progress.verifiedBranchCode,
      accountNumber: progress.verifiedAccountNumber,
    );
  }

  /// Stage 2's row list — the channels `ContactChannelsResult`/`LocalProgress.channelsSummary`
  /// cached, minus `declined` ones (customer.md: "A customer who deselected WhatsApp never sees a
  /// WhatsApp row" — the same filter `SessionPendingScreen` applied inline before this method
  /// existed). Returns an empty list only if `LocalProgress` itself is missing, which
  /// `ChannelVerificationScreen` should never actually hit (unreachable without one, same
  /// reasoning as [verifiedAccount]).
  Future<List<ChannelSummary>> pendingVerificationChannels() async {
    final progress = await (_db.select(
      _db.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    if (progress == null) return const [];
    return ChannelSummary.decodeList(
      progress.channelsSummary,
    ).where((c) => c.state != ChannelState.declined).toList();
  }

  /// The profile id `submitContactChannels` cached — what every Stage 2 call acts against.
  /// `null` only when `LocalProgress` is missing, same unreachable-in-practice caveat as
  /// [pendingVerificationChannels].
  Future<String?> verifyingProfileId() async {
    final progress = await (_db.select(
      _db.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    return progress?.profileId;
  }

  /// Stage 2's per-channel verify action. On [OtpVerifyOutcome.verified], write-through the cached
  /// `channelsSummary` so a later resume shows this channel as already verified instead of
  /// re-asking (`EntryRepository`'s existing display-only-cache pattern, extended — see the S5-04
  /// session report's design notes). Every other outcome is left to `ChannelVerificationScreen`'s
  /// own ephemeral, per-screen-session state (locked/wrong-code/expired are not persisted; see
  /// that screen's doc comment for why this is a disclosed limitation, not an oversight).
  Future<OtpVerifyResult> verifyChannel({
    required String channel,
    required String code,
  }) async {
    final profileId = await _requireVerifyingProfileId();
    final result = await _api.verifyChannel(
      profileId: profileId,
      channel: channel,
      code: code,
    );
    if (result.outcome == OtpVerifyOutcome.verified) {
      await _markChannelVerified(channel);
    }
    return result;
  }

  /// Stage 2's per-channel resend action. [OtpResendOutcome.alreadyVerified] write-throughs the
  /// cache exactly like a direct [OtpVerifyOutcome.verified] would — the self-correcting path for
  /// a channel that was actually verified before this screen last resumed (see
  /// [pendingVerificationChannels]'s caveat).
  /// [correctedEmailAddress] is the customer's in-place fix for a mistyped address (BL-101). Only
  /// ever forwarded for the email channel — asserted rather than silently dropped, because the
  /// backend answers `400` for a correction on any other channel and a caller that supplies one
  /// for `sms`/`whatsapp` has a logic bug worth surfacing in development.
  ///
  /// Two write-throughs beyond the [OtpResendOutcome.alreadyVerified] one, both needed because the
  /// backend applies a correction even when it then REFUSES the resend — with
  /// [OtpResendOutcome.alreadyVerified] itself as the single exception, where it does not apply
  /// the correction at all and neither write may happen:
  /// - the new masked destination into `channelsSummary`, so a resume after a correction shows the
  ///   corrected address rather than the typo;
  /// - the new address into `LocalDraft`, so a second correction prefills what the customer last
  ///   entered instead of re-presenting the mistake they already fixed.
  Future<OtpResendResult> resendChannel({
    required String channel,
    String? correctedEmailAddress,
  }) async {
    assert(
      correctedEmailAddress == null || channel == 'email',
      'a corrected email address is only valid for the email channel (got "$channel")',
    );
    final profileId = await _requireVerifyingProfileId();
    final result = await _api.resendChannel(
      profileId: profileId,
      channel: channel,
      correctedEmailAddress: correctedEmailAddress,
    );
    if (result.outcome == OtpResendOutcome.alreadyVerified) {
      await _markChannelVerified(channel);
    }
    // `alreadyVerified` is the ONE outcome on which the backend does not apply the correction:
    // `OtpVerificationService.reserveResend` skips it for a VERIFIED channel, to protect the
    // "verified proves this destination" invariant. Writing either value through on that outcome
    // would leave the app holding an address the backend explicitly refused to store — and
    // `LocalDraft.emailAddress` is what `ContactChannelsScreen` prefills and re-submits on a
    // Stage 1b re-entry, so the wrong value would not stay local. Found by `@agent-reviewer`,
    // S8-10.
    if (correctedEmailAddress != null && result.outcome != OtpResendOutcome.alreadyVerified) {
      await _updateChannelMask(channel, result.maskedDestination);
      await saveDraft((await loadDraft()).copyWith(emailAddress: correctedEmailAddress));
    }
    return result;
  }

  /// [verifyChannel]/[resendChannel] both need the id; a clearly-named failure here beats a bare
  /// `!` crashing with an unrelated-looking `TypeError` if `LocalProgress` were ever missing
  /// (found under review, S5-04 — the screen's own generic-error catch already contains the blast
  /// radius either way, since this is not reachable through normal use).
  Future<String> _requireVerifyingProfileId() async {
    final profileId = await verifyingProfileId();
    if (profileId == null) {
      throw StateError(
        'no in-progress verification session (LocalProgress.profileId is unset)',
      );
    }
    return profileId;
  }

  /// Runs the read-modify-write inside a drift transaction — found under review, S5-04: two
  /// channel rows can genuinely verify concurrently (`ChannelVerificationScreen`'s submit guard is
  /// per-row, not per-screen), and an unsynchronised read-modify-write of the whole
  /// `channelsSummary` string would let the second write clobber the first channel's `verified`
  /// state with its own stale snapshot.
  Future<void> _markChannelVerified(String channel) {
    return _db.transaction(() async {
      final progress = await (_db.select(
        _db.localProgress,
      )..where((t) => t.id.equals(0))).getSingleOrNull();
      if (progress == null) return;
      final updated = ChannelSummary.decodeList(progress.channelsSummary)
          .map(
            (c) => c.channel == channel
                ? ChannelSummary(
                    channel: c.channel,
                    state: ChannelState.verified,
                    maskedDestination: c.maskedDestination,
                  )
                : c,
          )
          .toList();
      await (_db.update(_db.localProgress)..where((t) => t.id.equals(0))).write(
        LocalProgressCompanion(
          channelsSummary: Value(ChannelSummary.encodeList(updated)),
        ),
      );
    });
  }

  /// Write-through for a corrected destination (BL-101), the mask counterpart of
  /// [_markChannelVerified].
  ///
  /// **Runs in a drift transaction because this method's own guard is load-bearing, not because
  /// [_markChannelVerified] has one.** Stated at length because the first version of this comment
  /// got it wrong in the direction that would licence a later session to delete the transaction.
  ///
  /// `channelsSummary` is a single encoded string holding every channel's row, so every write to
  /// it is a whole-list read-modify-write, and `ChannelVerificationScreen`'s submit guards are
  /// per-row (`row.submitting`/`row.resendSubmitting`), never per-screen — so an email correction
  /// can genuinely overlap a phone row's verify. The interleaving only THIS transaction prevents:
  ///
  /// 1. this method's `SELECT` completes and the `await` yields;
  /// 2. [_markChannelVerified]'s transaction opens and runs to completion, marking the phone row
  ///    verified;
  /// 3. this method writes the snapshot it read at step 1 — discarding that verification.
  ///
  /// [_markChannelVerified]'s transaction cannot prevent that: it serialises what is queued behind
  /// it, not a read already taken before it opened.
  ///
  /// The accompanying concurrency test does NOT reach that ordering — established by reverting
  /// each guard in turn at S8-10: removing both makes it fail, removing only this one does not,
  /// because `FakeEntryApi` returns synchronously and the verify's transaction happens to open
  /// first. So the test pins the pair, and this ordering is argued rather than covered. The case
  /// that would isolate it — two concurrent [_updateChannelMask] calls — is genuinely unreachable
  /// (one email row, and `resendSubmitting` serialises it against itself), so no test is written
  /// for it. Found by `@agent-reviewer`, S8-10, second pass.
  Future<void> _updateChannelMask(String channel, String maskedDestination) {
    return _db.transaction(() async {
      final progress = await (_db.select(
        _db.localProgress,
      )..where((t) => t.id.equals(0))).getSingleOrNull();
      if (progress == null) return;
      final updated = ChannelSummary.decodeList(progress.channelsSummary)
          .map(
            (c) => c.channel == channel
                ? ChannelSummary(
                    channel: c.channel,
                    state: c.state,
                    maskedDestination: maskedDestination,
                  )
                : c,
          )
          .toList();
      await (_db.update(_db.localProgress)..where((t) => t.id.equals(0))).write(
        LocalProgressCompanion(
          channelsSummary: Value(ChannelSummary.encodeList(updated)),
        ),
      );
    });
  }

  /// Stage 2's "Next" action, already confirmed eligible (at least one phone channel verified) and,
  /// if needed, already confirmed past the unverified-channel dialog, AND (S5-05) already confirmed
  /// past `DataEntryRepository.prepareCatalog()` — the real Stage 2→3 boundary
  /// (reference-data.md client rule 3). Advances `resumeStage` straight to Stage 3 — there is no
  /// longer an intermediate `'verified'` resting state — `channelsSummary`/`profileId` are already
  /// current via [verifyChannel]/[resendChannel]'s write-through.
  Future<void> completeVerification() async {
    await (_db.update(_db.localProgress)..where((t) => t.id.equals(0))).write(
      LocalProgressCompanion(resumeStage: Value(_resumeStageStage3)),
    );
  }

  /// Stage 0's launch check. Reads `LocalProgress` — its mere presence is what "an in-progress
  /// session" means here (see `LocalProgress`'s own doc comment); a bare `LocalDraft` with no
  /// `LocalProgress` is just a form the customer hasn't finished typing yet, not a session to
  /// resume, and is handled with no backend call at all.
  ///
  /// **CLOSED at S8-14 (BL-021). The comment that stood here until then was false and had been
  /// for two sprints** — it said `account-check` "still has no wire signal" for customer.md
  /// Stage 0's third answer and that a relaunch during a block "self-corrects". The backend has
  /// answered `BLOCKED` with `blockedUntil` since S4-06; this client simply did not carry the
  /// enum value, so the decode threw and the customer was shown a connectivity error. The comment
  /// froze the pre-S4-06 truth and stopped three later sessions checking, which is exactly what
  /// CLAUDE.md's deferred-mobile-half rule now exists to prevent.
  ///
  /// `BLOCKED` is now consumed here and returned as [LaunchBlocked]. The lock is temporary — the
  /// backend only sends it while `phone_lock_until` is still in the future — so **local state is
  /// deliberately not cleared on this path**: the block must cost the customer a wait, never their
  /// draft.
  Future<LaunchDecision> launchDecision() async {
    final progress = await (_db.select(
      _db.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();

    if (progress == null) {
      final draft = await loadDraft();
      return FreshStart(
        draftBranchCode: draft.branchCode,
        draftAccountNumber: draft.accountNumber,
      );
    }

    AccountCheckResult result;
    try {
      result = await _api.checkAccount(
        progress.verifiedBranchCode,
        progress.verifiedAccountNumber,
      );
    } on BackendUnreachableException {
      return _resumeOffline(progress);
    }

    switch (result.continuation) {
      case AccountContinuation.proceed:
        return _resumeOnline(progress);
      case AccountContinuation.terminal:
        await _clearSession();
        // BL-123. Two different terminal facts, and only the first one is about the ACCOUNT.
        // `inactive` keeps its message here because it is true and specific. Everything else is
        // a finished profile — submitted, approved, rejected or terminated, indistinguishable on
        // this wire by deliberate design (BL-119) — and gets `LaunchEnded`, which names no
        // message so no caller can claim an outcome. It used to claim "already completed", which
        // was false for three of those four.
        return result.outcome == AccountOutcome.inactive
            ? const LaunchTerminal('الحساب غير نشط. يرجى مراجعة أقرب فرع.')
            : const LaunchEnded();
      case AccountContinuation.retry:
        // The account this session was validated against can no longer be found — nothing left
        // to resume. Defensive fallback, not expected in practice (see the S5-02 session report).
        await _clearSession();
        return const FreshStart();
      case AccountContinuation.blocked:
        // BL-021. No `_clearSession()` here, deliberately — see this method's own comment above.
        return LaunchBlocked(blockedUntil: result.blockedUntil);
    }
  }

  LaunchDecision _resumeOnline(LocalProgressData progress) =>
      _resume(progress, offline: false);

  LaunchDecision _resumeOffline(LocalProgressData progress) =>
      _resume(progress, offline: true);

  LaunchDecision _resume(LocalProgressData progress, {required bool offline}) {
    final channels = ChannelSummary.decodeList(progress.channelsSummary);
    DataEntryStage? dataEntryStage;
    for (final stage in DataEntryStage.values) {
      if (stage.name == progress.resumeStage) {
        dataEntryStage = stage;
        break;
      }
    }
    IdentityScanStage? identityScanStage;
    for (final stage in IdentityScanStage.values) {
      if (stage.name == progress.resumeStage) {
        identityScanStage = stage;
        break;
      }
    }
    switch (progress.resumeStage) {
      // S5-08 repurposed this value from "past everything this app builds" to "somewhere in
      // stages 10-12". No legacy branch is needed, unlike `_resumeStageBeyondStage6` below: a
      // device holding this under an S5-07 build had finished Stage 9 and had Stage 10 next, which
      // is exactly what it means now. The old destination (`/session-pending`) was a placeholder
      // for stages that did not exist yet; they exist.
      case resumeStageBeyondStage9:
        return ResumeFinalStages(offline: offline);
      // A device that reached this exact point under an S5-05/S5-06 build still has this value on
      // disk. It meant "past everything this app builds" when nothing beyond stage 6 existed;
      // now that Stage 7 exists it means precisely "stage 6 is done, stage 7 is next" — the same
      // legacy-value handling the 'verified' case below already does for the S5-04 generation.
      // Without it, a customer who completed stage 6 on the previous build lands on the
      // post-Stage-9 placeholder and can never reach the identity scan at all.
      case _resumeStageBeyondStage6:
        return ResumeDataEntry(stage: DataEntryStage.stage7, offline: offline);
      case _ when identityScanStage != null:
        return ResumeIdentityScan(stage: identityScanStage, offline: offline);
      case _resumeStageAwaitingVerification:
        return ResumeAwaitingVerification(
          profileId: progress.profileId!,
          channels: channels,
          offline: offline,
        );
      // A device that reached this exact point under an S5-04 build (before S5-05 retired the
      // 'verified' resting state in favour of resuming straight into stage 3) still has this
      // value on disk — found under review, S5-05, second pass: without this case it falls to
      // `default` below and wrongly sends a Stage-2-complete customer back to contact-channel
      // selection. 'verified' meant exactly what 'stage3' means today (channels verified,
      // nothing beyond built yet at the time), so it maps the same way `completeVerification()`
      // itself now writes.
      case 'verified':
        return ResumeDataEntry(stage: DataEntryStage.stage3, offline: offline);
      case _ when dataEntryStage != null:
        return ResumeDataEntry(stage: dataEntryStage, offline: offline);
      default:
        return ResumeContactChannels(
          branchCode: progress.verifiedBranchCode,
          accountNumber: progress.verifiedAccountNumber,
          offline: offline,
        );
    }
  }

  /// customer.md Stage 13 "Abandonment" — clears every session-scoped table, returning the app
  /// to a fresh start. Does NOT delete the backend's profile (it stays resumable through Stage
  /// 1a under the review flow), only the on-device copy.
  Future<void> abandon() => _clearSession();

  /// customer.md Stage 12 "After submission" — "Local state cleared ... The app returns to a fresh
  /// start. The next customer on the same device begins at stage 1a with nothing carried over."
  ///
  /// Mechanically identical to [abandon] and deliberately a separate name: these are opposite
  /// events (one is giving up, one is finishing) that happen to need the same clear, and a call
  /// site reading `abandon()` after a successful submission would be actively misleading to the
  /// next person. customer.md is explicit that the clear is a privacy requirement on a shared
  /// device, not storage hygiene.
  ///
  /// **Step 5 of Stage 12's strict ordering, and the ordering is load-bearing.** This runs only
  /// after the backend has confirmed the submission AND after the confirmation screen holds its
  /// reference number and channels in memory — clearing first would destroy the `profileId` that
  /// `POST /submission/current` needs, and the reference number is the customer's only artifact
  /// once this returns. See `Stage12Screen`.
  Future<void> clearAfterSubmission() => _clearSession();

  /// The one place every `_db.clear()` in this class goes through — found under review, S5-05,
  /// second pass: the salary-certificate file-delete fix had only been wired into [abandon],
  /// leaving `launchDecision()`'s own two direct `_db.clear()` calls (a terminal/inactive
  /// account, and the defensive `retry` fallback) still orphaning the file on exactly the same
  /// exposure the fix exists to close — the terminal case if anything being the MORE likely one
  /// a real customer hits (relaunching after finishing the journey).
  ///
  /// Deletes the locally-captured salary-certificate FILE, if one exists, AFTER the database
  /// clear — `_db.clear()` deletes `DataEntryDraft`'s row (the path), but the file itself lives
  /// outside the encrypted `session.sqlite` (`getApplicationSupportDirectory()`, plain disk) and
  /// is never otherwise removed, leaving real PII readable on the device indefinitely once
  /// nothing references it — exactly the exposure S5-03/R-025 closed for everything else this
  /// table holds. The delete is best-effort: a `FileSystemException` (a TOCTOU race, a platform
  /// permission quirk) must not stop the session from actually clearing, so it is caught and
  /// swallowed rather than left to propagate into a caller (`ChannelVerificationScreen._abandon`
  /// and siblings) that has no matching `catch` and would otherwise never reach its own
  /// `context.go('/account-entry')`.
  Future<void> _clearSession() async {
    final draft = await (_db.select(
      _db.dataEntryDraft,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    final certificatePath = draft?.salaryCertificatePath;
    await _db.clear();
    if (certificatePath != null) {
      try {
        final file = File(certificatePath);
        if (await file.exists()) await file.delete();
      } on FileSystemException {
        // Best-effort cleanup — see this method's own doc comment.
      }
    }
  }
}
