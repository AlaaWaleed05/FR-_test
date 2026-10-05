/// The Stage 10-12 journey pointer — "where is this journey", as the backend answers it.
///
/// **Why this lives in `core/journey/` and not inside a stage's own package.** Four different
/// screens read it: the resume gate that lands a returning customer, Stage 10's post-`/terminated`
/// block probe, Stage 11's entry guard, and Stage 12's pre-submit check plus its confirmation
/// screen. It is not Stage 12's private business even though it happens to be served by
/// `SubmissionController`.
library;

/// Where the customer stands in Stages 10-12, mirroring the backend's
/// `submission/domain/JourneyStage.java` exactly.
///
/// **This is a PLACEMENT answer, never an AUTHORIZATION.** It says where a journey has got to; it
/// does not say that the person holding the phone is entitled to be there. The distinction is not
/// pedantic — see [JourneyPointer]'s own comment for the concrete way getting it wrong would let an
/// impostor through.
///
/// Deliberately not a screen name, matching the backend's own note: the backend says where the
/// customer is, the app owns the mapping to a screen.
enum JourneyStage {
  /// Stage 10 is available and has not passed yet.
  liveness('LIVENESS'),

  /// The Stage 10 budget is spent; the pointer carries when the block lifts.
  livenessBlocked('LIVENESS_BLOCKED'),

  /// Stage 10 passed, no signature stored yet — the customer is at Stage 11.
  signature('SIGNATURE'),

  /// Liveness passed and a signature is stored — the customer is at Stage 12, not yet submitted.
  submit('SUBMIT'),

  /// Submitted and awaiting operator review. Carries the reference number and channels.
  submitted('SUBMITTED'),

  /// Reviewed and accepted. Carries the reference number and channels.
  approved('APPROVED'),

  /// Reviewed and refused. Carries the reference number and channels.
  rejected('REJECTED');

  const JourneyStage(this.wire);

  final String wire;

  /// Throws on an unrecognised value rather than degrading.
  ///
  /// The opposite call from [JourneyCode.unknown]'s, and deliberately so: an unrecognised ERROR
  /// code is already an error path, where crashing would replace a degraded message with no
  /// message. An unrecognised STAGE is a successful `200` whose meaning this app version does not
  /// understand, and guessing a screen from it is how a customer ends up somewhere they should not
  /// be. Same reasoning `DioEntryApi` applies to its own wire enums.
  static JourneyStage fromWire(String value) {
    for (final stage in JourneyStage.values) {
      if (stage.wire == value) return stage;
    }
    throw FormatException('unknown journey stage: $value');
  }

  /// The three stages that mean the customer's journey is finished. All three carry a reference
  /// number and the verified channels.
  bool get isComplete =>
      this == JourneyStage.submitted ||
      this == JourneyStage.approved ||
      this == JourneyStage.rejected;
}

/// One answer from `POST /api/v1/submission/current`.
///
/// **Placement, not authorization — the AD-008/BL-041 line, stated where it is easiest to cross.**
/// [stage] answering [JourneyStage.signature] or [JourneyStage.submit] is derived server-side from
/// `facePassed`, so it is tempting to treat it as "liveness is done, skip Stage 10". Today that is
/// harmless only because reaching this call at all requires a `profileId` held in local storage,
/// which a fresh device does not have. When BL-041 (AD-008's device-less supersession) lands, a
/// new-device re-entry WILL be able to reach a profile whose prior `face_result` it inherited —
/// and a "the backend says facePassed, so skip Stage 10" shortcut would then let an impostor skip
/// the liveness check entirely. `BACKLOG.md` BL-041 records that inheritance explicitly.
///
/// So: Stage 10 entry is driven by device-local resume state, this pointer is consulted only when
/// local state already holds the profile id, and nothing in this app treats [stage] as permission.
class JourneyPointer {
  const JourneyPointer({
    required this.profileId,
    required this.stage,
    this.blockedUntil,
    this.referenceNumber,
    this.verifiedChannels = const [],
  });

  final String profileId;
  final JourneyStage stage;

  /// Non-null only for [JourneyStage.livenessBlocked]. May be an instant already in the past — the
  /// reset is lazy and the pointer reports stored state, so this is rendered defensively.
  final DateTime? blockedUntil;

  /// Non-null only for the three complete stages. The customer's only artifact once local storage
  /// is cleared (customer.md:1004).
  final String? referenceNumber;

  /// Channel wire values only (`sms`/`whatsapp`/`email`) — never a phone number or an email
  /// address.
  ///
  /// **This is the confirmation screen's ONLY source for them, on every path.** Not
  /// `SubmissionResponse.verifiedChannels`, which means "channels this call enqueued a notification
  /// for" and is correctly EMPTY on an idempotent re-submit (BL-058). Branching on whether the
  /// submit response carried a non-empty list is precisely the two-sources bug S5-13 built this
  /// endpoint to remove, so this app does not branch: it reads them here after a fresh submit, after
  /// a lost-acknowledgement retry, and on resume alike.
  final List<String> verifiedChannels;
}
