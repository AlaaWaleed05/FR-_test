/// Mirrors the backend's `AccountCheckOutcome` (`accountcheck/domain/AccountCheckOutcome.java`)
/// wire values exactly — `ACTIVE`/`INACTIVE`/`INVALID`.
enum AccountOutcome { active, inactive, invalid }

/// Mirrors the backend's `AccountCheckContinuation` wire values exactly —
/// `PROCEED`/`TERMINAL`/`RETRY`/`BLOCKED`.
///
/// **`blocked` was missing until S8-14, and its absence was BL-021.** The backend has answered
/// `BLOCKED` since S4-06; this enum did not carry the value, so `values.byName('blocked')` threw
/// an `ArgumentError` out of the decode, which surfaced to the customer as "check your internet
/// connection" on the launch screen and as an account-number field error at Stage 1a. **Every
/// value the backend can send must exist here** — the decode is deliberately strict (see
/// `DioEntryApi.checkAccount`) so drift fails loudly rather than being silently ignored, and the
/// lesson of BL-021 is that a loud failure still needs an honest screen behind it.
enum AccountContinuation { proceed, terminal, retry, blocked }

/// What `POST /api/v1/account-check` returned, decoded from the wire response.
class AccountCheckResult {
  const AccountCheckResult({
    required this.outcome,
    required this.continuation,
    required this.requestId,
    this.blockedUntil,
  });

  final AccountOutcome outcome;
  final AccountContinuation continuation;
  final String requestId;

  /// When the phone lock lifts. Non-null only when [continuation] is
  /// [AccountContinuation.blocked] — `AccountCheckResponse.blockedUntil`, which the backend sets
  /// only while the lock is still in the future (`AccountCheckService.check` filters on
  /// `isAfter(now)`), so the block genuinely self-heals once it passes.
  final DateTime? blockedUntil;
}

/// Mirrors the backend's `ChannelState` wire values (`profile/domain/ChannelState.java`) —
/// `unverified`, `declined`, `verified`. Stage 1b's response only ever carries the first two (see
/// `ChannelInfo`'s own Javadoc); `verified` is Stage 2's, once a channel's code is checked.
enum ChannelState { unverified, declined, verified }

/// One row of `ContactChannelsResponse.channels` — channel name, its state, and its masked
/// destination (e.g. `•••• 4821`), never the raw destination or the OTP code.
class ChannelSummary {
  const ChannelSummary({
    required this.channel,
    required this.state,
    required this.maskedDestination,
  });

  final String channel;
  final ChannelState state;
  final String maskedDestination;

  /// `channel:state:maskedDestination`, joined with `|` across a list — the whole encoding
  /// `LocalProgress.channelsSummary` persists. Kept deliberately simple (no JSON dependency) since
  /// this is a small, flat, display-only cache, never re-parsed as anything authoritative.
  static String encodeList(List<ChannelSummary> channels) {
    return channels
        .map((c) => '${c.channel}:${c.state.name}:${c.maskedDestination}')
        .join('|');
  }

  static List<ChannelSummary> decodeList(String? encoded) {
    if (encoded == null || encoded.isEmpty) return const [];
    return encoded.split('|').map((entry) {
      final parts = entry.split(':');
      return ChannelSummary(
        channel: parts[0],
        state: ChannelState.values.byName(parts[1]),
        maskedDestination: parts.sublist(2).join(':'),
      );
    }).toList();
  }
}

/// What `POST /api/v1/contact-channels` returned on success.
class ContactChannelsResult {
  const ContactChannelsResult({
    required this.profileId,
    required this.channels,
  });

  final String profileId;
  final List<ChannelSummary> channels;
}

/// Mirrors the backend's `VerificationOutcome` (`otpverification/domain/VerificationOutcome.java`)
/// — all four are legitimate, expected states the UI renders inline; none is an HTTP error (the
/// endpoint is `200` always, per `OtpVerificationController`'s own doc comment).
enum OtpVerifyOutcome { verified, wrongCode, expired, channelLocked }

/// Mirrors the backend's `ResendOutcome` (`otpverification/domain/ResendOutcome.java`).
enum OtpResendOutcome {
  issued,
  capExhausted,
  tooSoon,
  channelLocked,
  alreadyVerified,
}

/// What `POST /api/v1/otp/verify` returned.
class OtpVerifyResult {
  const OtpVerifyResult({
    required this.channel,
    required this.outcome,
    required this.state,
    required this.sessionBlockedUntil,
  });

  final String channel;
  final OtpVerifyOutcome outcome;
  final ChannelState state;

  /// Non-null only when every selected phone channel has locked (S3-08/R-044) — the session-wide
  /// block, not this one channel's own lock. `null` on every other outcome.
  final DateTime? sessionBlockedUntil;
}

/// What `POST /api/v1/otp/resend` returned.
class OtpResendResult {
  const OtpResendResult({
    required this.channel,
    required this.outcome,
    required this.maskedDestination,
    required this.secondsUntilAllowed,
  });

  final String channel;
  final OtpResendOutcome outcome;
  final String maskedDestination;

  /// Only meaningful when [outcome] is [OtpResendOutcome.tooSoon] — the backend's own live count,
  /// never a client-guessed schedule (customer.md Policy values: 30s/60s/120s is enforced and
  /// reported server-side; the UI only ever reflects what it is told). `null` on every other
  /// outcome (the wire's `-1` sentinel, mapped away at this boundary).
  final int? secondsUntilAllowed;
}

/// A `400` from either `/api/v1/otp/verify` or `/api/v1/otp/resend` — mirrors the backend's
/// `UnknownOtpChannelException` (`otpverification/domain/UnknownOtpChannelException.java`): no
/// `app.profile_channel` row exists for this profile/channel pair to act on. Not reachable through
/// this app's own screens, which only ever call these endpoints with a channel the profile was
/// actually offered — kept because the endpoint's contract includes it, same reasoning as
/// [SessionTemporarilyBlockedException].
class UnknownOtpChannelException implements Exception {
  const UnknownOtpChannelException();
}

/// Thrown by [EntryApi] methods for a connection-class failure — the backend could not be
/// reached at all, as opposed to the backend answering with a business rejection. Callers use
/// this specifically to distinguish "show the offline indicator" from "show a field/rule error".
class BackendUnreachableException implements Exception {
  const BackendUnreachableException(this.message);

  final String message;

  @override
  String toString() => 'BackendUnreachableException: $message';
}

/// A `400` from `POST /api/v1/contact-channels`. The backend's error body carries no `detail` —
/// `ContactChannelsController` returns a plain Spring default-error JSON with no message field
/// (verified live, S5-02) — so the client cannot distinguish *which* of several possible causes
/// produced it: no phone channel selected/available (`NoPhoneChannelSelectedException`), a blank/
/// oversized/control-character field, or an unparseable phone number
/// (`InvalidPhoneNumberException`/`PhoneNumberNormalizer`). Found under review, S5-02: an earlier
/// version of this client mapped every 400 to a specific "select a channel" message regardless of
/// actual cause, which is wrong and unactionable for, say, a malformed phone number. The app's own
/// client-side gating (non-empty phone, at least one phone channel selected) is meant to make this
/// mostly unreachable in practice; this exists so the server-side rule is still handled with an
/// honest, generic message rather than a specific, possibly false one.
class ContactChannelsRejectedException implements Exception {
  const ContactChannelsRejectedException();
}

/// Mirrors `ProfileAlreadyCompleteException` — 409: the account's profile has already reached a
/// terminal status.
class ProfileAlreadyCompleteException implements Exception {
  const ProfileAlreadyCompleteException();
}

/// Mirrors `SessionTemporarilyBlockedException` — 429: Stage 2's escalating phone-lock (S3-08,
/// R-044) is still active. Not reachable through this session's own screens (Stage 2 doesn't
/// exist yet to ever set the lock), handled anyway because the endpoint's contract includes it.
class SessionTemporarilyBlockedException implements Exception {
  const SessionTemporarilyBlockedException();
}

/// Stage 0's decision (docs/journeys/customer.md Stage 0 / Stage 13), computed by
/// `EntryRepository.launchDecision()`.
sealed class LaunchDecision {
  const LaunchDecision();
}

/// No local state — Stage 1a fresh. [draftBranchCode]/[draftAccountNumber] pre-fill the form
/// only if a `LocalDraft` row exists despite there being no `LocalProgress` (a half-typed,
/// never-submitted 1a draft).
class FreshStart extends LaunchDecision {
  const FreshStart({this.draftAccountNumber});

 
  final String? draftAccountNumber;
}

/// 1a passed, 1b not yet submitted — resume directly on the Stage 1b screen.
class ResumeContactChannels extends LaunchDecision {
  const ResumeContactChannels({
    
    required this.accountNumber,
    required this.offline,
  });

  final String accountNumber;

  /// True when the backend could not be reached to confirm this resume — the screen must show
  /// the offline indicator and disable backend-dependent actions up front (customer.md Stage 0).
  final bool offline;
}

/// 1b submitted, profile created, OTPs sent, Stage 2 not yet satisfied — resume directly on the
/// Stage 2 channel-verification screen. [channels] is the last cached snapshot
/// (`EntryRepository`'s write-through cache — display-only, refreshed as verify/resend calls learn
/// more, never itself authoritative); a channel locked or mid-countdown when the app was last
/// backgrounded shows as plain `unverified` again here (disclosed, not fixed — see
/// `ChannelVerificationScreen`'s own doc comment).
class ResumeAwaitingVerification extends LaunchDecision {
  const ResumeAwaitingVerification({
    required this.profileId,
    required this.channels,
    required this.offline,
  });

  final String profileId;
  final List<ChannelSummary> channels;
  final bool offline;
}

/// The five customer-entered data stages, in journey order — mirrors `DataEntryDraft`/
/// `PendingStageSync`'s own `'stage3'..'stage7'` string values (S5-05; `stage7` added at S5-07).
///
/// Stage 7 belongs here and not with the identity-scan stages because the value it collects — the
/// identity document type — is customer-entered, device-owned data (customer.md Stage 13's
/// ownership table) submitted through `POST /api/v1/data-entry/stage7` like its four siblings. It
/// is also the last freely-revisitable stage: customer.md Stage 7's closing note calls it "the
/// boundary", after which every step consumes a real Uqudo operation.
enum DataEntryStage { stage3, stage4, stage5, stage6, stage7 }

/// The two identity-scan stages (customer.md Stages 8-9) — mirrors the `'stage8'`/`'stage9'`
/// values `IdentityScanRepository.advanceToStage` writes.
///
/// Kept separate from [DataEntryStage] rather than appended to it, because these two are not the
/// same kind of thing: they are online-only, they consume a metered external operation, and their
/// real state is backend-owned. A device that resumes here re-reads that state rather than
/// trusting its own pointer.
enum IdentityScanStage { stage8, stage9 }

/// `LocalProgress.resumeStage`'s value once Stage 9 has been accepted — **"the customer is
/// somewhere in stages 10-12; ask the backend which"**.
///
/// **Repurposed at S5-08, and it needs no legacy branch.** It previously meant "past everything
/// this app version builds" and routed to the post-journey placeholder, exactly as
/// `'beyondStage6'` once meant that before Stage 7 existed. The difference is that
/// `'beyondStage6'` needed a legacy case (its old meaning, "past the end", and its new one,
/// "resume at Stage 7", are different places) whereas this value's old and new meanings coincide:
/// a device holding it under an S5-07 build had finished Stage 9 and had Stage 10 next, which is
/// precisely what it means now. Nothing on disk has to be migrated.
///
/// **One value for three stages, deliberately.** Stages 8 and 9 each get their own; 10, 11 and 12
/// share this one because their real state is backend-owned in every respect — whether liveness
/// passed, whether a signature is stored, whether the profile is submitted, whether a block is
/// running — and customer.md Stage 13 requires every resume to begin by asking. A per-stage local
/// pointer would be a second source for facts the device does not own. `FinalStagesGateScreen`
/// performs the single read that resolves it.
const resumeStageBeyondStage9 = 'beyondStage9';

/// Stage 2 satisfied (channels verified) and stage 3-6's device-owned progress (customer.md Stage
/// 13's ownership table: "progress" is customer-entered, device wins) points at [stage] as the
/// furthest one reached — resume directly on that stage's screen. Back-navigation between stages
/// 3-6 never moves this pointer (only an explicit "Next" does), so relaunching while browsing
/// backward resumes at the furthest stage reached, not wherever the customer was last looking —
/// a deliberate simplicity choice, matching how stage 0-2's own resume point already only ever
/// advances on an explicit forward action.
class ResumeDataEntry extends LaunchDecision {
  const ResumeDataEntry({required this.stage, required this.offline});

  final DataEntryStage stage;
  final bool offline;
}

/// Stage 7 submitted — resume directly on Stage 8 or Stage 9.
///
/// **The pointer says which screen to open; it does not say what is true.** Stages 8-9's real
/// state is backend-owned (customer.md Stage 13's ownership table: "Uqudo results, Civil Registry
/// data" are backend-wins), so `Stage9Screen` re-reads it through
/// `POST /registry-review/current` on arrival rather than rendering from anything carried here,
/// and a `STATE_CONFLICT` from that read is what corrects a stale pointer.
///
/// There is no `offline` variant that proceeds: both stages need the network, and a customer who
/// gets here without it is told so rather than being walked into a scan that cannot start.
class ResumeIdentityScan extends LaunchDecision {
  const ResumeIdentityScan({required this.stage, required this.offline});

  final IdentityScanStage stage;
  final bool offline;
}

/// **Unreachable since S5-08 — retained, not live.**
///
/// This decision existed for each successive "past everything this build has" resting point: after
/// Stage 2 (`'verified'`, pre-S5-05), after Stage 6 (`'beyondStage6'`, pre-S5-07), then after
/// Stage 9 (`resumeStageBeyondStage9`, pre-S5-08). S5-08 built stages 10-12, so that last resting
/// point is no longer past the end — `[resumeStageBeyondStage9]` now yields [ResumeFinalStages]
/// and nothing in `lib/` constructs this class any more.
///
/// It and `SessionPendingScreen` are kept rather than deleted because the journey has acquired a
/// new "past everything" point three times, and each time this shape was what got repurposed.
/// Deleting them is a tidy-up, not this slice's business. **Do not add a new caller without
/// deciding what it means first** — the trap the previous three repurposings each had to think
/// through is that "past everything" and "at a specific stage" are different answers.
class ResumeVerified extends LaunchDecision {
  const ResumeVerified({
    required this.profileId,
    required this.verifiedChannels,
    required this.offline,
  });

  final String profileId;
  final List<ChannelSummary> verifiedChannels;
  final bool offline;
}

/// Stage 9 accepted — the customer is in stages 10-12 and only the backend knows which.
///
/// Carries no stage of its own on purpose. Unlike [ResumeDataEntry] and [ResumeIdentityScan], which
/// name the screen to open, this one names a QUESTION: `FinalStagesGateScreen` asks
/// `POST /api/v1/submission/current` and lands the customer on whatever it answers, including
/// straight to the confirmation screen with their reference number when the profile is already
/// submitted.
///
/// **A placement answer, never an authorization one.** See `FinalStagesGateScreen` for the
/// AD-008/BL-041 reasoning this must not cross.
class ResumeFinalStages extends LaunchDecision {
  const ResumeFinalStages({required this.offline});

  final bool offline;
}

/// The account is no longer ACTIVE — local state has already been cleared by the time this is
/// returned.
///
/// **Narrowed at S8-16 (BL-123): this no longer covers the already-complete profile.** It used to
/// carry both, discriminated by a message string chosen at the call site, which is exactly how the
/// false «تم استكمال…» sentence spread to fourteen places. The complete/terminal profile is now
/// [LaunchEnded], which names no message at all. [message] here is the inactive-account copy and
/// is TRUE, which is why this type survives rather than being folded in.
class LaunchTerminal extends LaunchDecision {
  const LaunchTerminal(this.message);

  final String message;
}

/// The backend answered `TERMINAL` for a profile that has already finished — submitted, approved,
/// rejected or terminated. Local state has been cleared by the time this is returned.
///
/// **Carries no message and no status, deliberately.** The wire has no discriminator (BL-119,
/// closed as unnecessary 2026-09-12), so any of four statuses can produce this; `EndedScreen`
/// owns the one sentence that is true for all four. Adding a field here would re-open the
/// fourteen-call-site problem this type was split out to close.
class LaunchEnded extends LaunchDecision {
  const LaunchEnded();
}

/// The backend answered `BLOCKED` — the customer's phone lock (Stage 2's escalating lock,
/// S3-08/R-044) is still live, so no stage may be entered or resumed until it lifts (BL-021).
///
/// **Local state is deliberately NOT cleared on this path**, unlike [LaunchTerminal]. The block is
/// temporary — 15 minutes on a first occurrence, an hour thereafter — and the customer's draft
/// must survive it. Clearing here would turn a wait into data loss.
class LaunchBlocked extends LaunchDecision {
  const LaunchBlocked({required this.blockedUntil});

  /// When the lock lifts. The backend only sends `BLOCKED` while this is in the future, but it is
  /// rendered defensively all the same — see `BlockedView`.
  final DateTime? blockedUntil;
}
