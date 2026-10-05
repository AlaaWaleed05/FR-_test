import '../database/session_database.dart';
import 'journey_api.dart';
import 'journey_models.dart';

/// Stages 10-12's shared read, plus Stage 12's submit.
///
/// Thin on purpose: the interesting decisions in this slice live in the screens (which stage maps
/// to which route) and in `LivenessRepository` (the SDK seam, the timeout, retention). What this
/// owns is the one rule that must not be re-implemented per screen — resolving the local
/// `profileId` and asking the backend where the journey stands.
///
/// **No offline queue**, same reason as Stages 8-9: customer.md Stage 7's closing note makes it the
/// boundary after which every step consumes a real external operation. All three of these stages
/// need the network, so `BackendUnreachableException` propagates to a screen rather than being
/// swallowed into a queue.
class JourneyRepository {
  JourneyRepository({
    required JourneyApi api,
    required SessionDatabase sessionDb,
  }) : _api = api,
       _db = sessionDb;

  final JourneyApi _api;
  final SessionDatabase _db;

  /// The locally-held profile id.
  ///
  /// **This is what keeps [currentPointer] a placement answer rather than an authorization one.**
  /// Every pointer read in this app is gated behind a profile id that is already on this device,
  /// which is why the pointer cannot today be used to skip a stage on a device that never did it.
  /// See [JourneyPointer]'s comment for what changes when BL-041 lands.
  Future<String> requireProfileId() async {
    final progress = await (_db.select(
      _db.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    final id = progress?.profileId;
    if (id == null) {
      throw StateError(
        'no in-progress session (LocalProgress.profileId is unset)',
      );
    }
    return id;
  }

  /// Asks the backend where this journey stands. Non-mutating — see [JourneyApi.currentPointer].
  ///
  /// customer.md Stage 13: "The backend alone decides whether the session is still open. Every
  /// resume begins by asking." This is that ask for Stages 10-12, and it is the ONLY thing this app
  /// uses to decide which of those three screens a returning customer lands on. Nothing infers it
  /// from local state.
  Future<JourneyPointer> currentPointer() async =>
      _api.currentPointer(await requireProfileId());

  /// Stage 12's submit. Terminal.
  Future<SubmissionReceipt> submit() async =>
      _api.submit(await requireProfileId());
}
