import '../entry/entry_models.dart' show BackendUnreachableException;
import 'journey_error.dart';
import 'journey_models.dart';

/// Transport boundary for `backend/.../submission/web/SubmissionController` — the Stage 10-12
/// resume read and Stage 12's submit.
///
/// **Every method can throw the same five**, rather than repeating the list on each:
/// [JourneyConflictException] (a coded `409`), [JourneyClientErrorException] (an uncoded `400`),
/// [JourneyProfileNotFoundException] (a `404`), [BackendUnreachableException] (connection-class
/// failures **and any 5xx**), and a rethrown `DioException` for anything else. See
/// [mapJourneyError], which is the single place that decides.
abstract class JourneyApi {
  /// `POST /api/v1/submission/current` — the Stage 13 resume read for Stages 10-12 (S5-13).
  ///
  /// **Non-mutating, and that is a property of the backend, not a hope.** `currentJourneyPointer`
  /// issues two plain `SELECT`s: no Uqudo call, no write, no status transition, no budget draw, no
  /// audit event, proven by an integration test that snapshots five row counts and reads twice.
  /// That is what makes it safe to call as a probe — which Stage 10 does after every terminated
  /// attempt, and which `POST /liveness/token` could never be used for, since it mints a real
  /// Uqudo Face Session.
  ///
  /// Answers for a terminal profile too, unlike the Stage 9 read: [JourneyStage.submitted] is its
  /// single most important answer, because a customer whose app died between submitting and
  /// rendering the confirmation screen has no other way to recover their reference number.
  Future<JourneyPointer> currentPointer(String profileId);

  /// `POST /api/v1/submission` — Stage 12's submit. **Terminal**: the profile moves to `submitted`
  /// and the customer's journey is over.
  ///
  /// Idempotent by construction (no client key — this backend achieves idempotency structurally
  /// throughout). A re-call returns the same reference number and status.
  ///
  /// **The returned [SubmissionReceipt] deliberately carries no channel list.** See its own
  /// comment: the confirmation screen's channels come from [currentPointer], always.
  Future<SubmissionReceipt> submit(String profileId);
}

/// What Stage 12's submit returns to the app.
///
/// **`verifiedChannels` is deliberately absent from this type.** The wire carries one —
/// `SubmissionResponse.verifiedChannels` — but it means "channels this call enqueued a notification
/// for" and is correctly EMPTY on an idempotent re-submit, which is exactly the customer who lost
/// the acknowledgement and retried (customer.md:998-999). Rather than decode a field the app must
/// never use and trust every future reader not to reach for it, this type does not have one. The
/// confirmation screen reads channels from `POST /submission/current` on every path, and there is
/// no "did the submit response carry channels" branch anywhere in this app to reintroduce BL-058.
class SubmissionReceipt {
  const SubmissionReceipt({
    required this.profileId,
    required this.referenceNumber,
    required this.status,
  });

  final String profileId;

  /// Shown prominently on the confirmation screen; the customer's only artifact once local storage
  /// clears, and what they quote at a branch.
  final String referenceNumber;

  /// `submitted` on the fresh path; may be any terminal status on an idempotent re-call.
  final String status;
}
