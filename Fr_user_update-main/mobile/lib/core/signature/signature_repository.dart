import '../journey/journey_repository.dart';
import 'signature_api.dart';
import 'signature_models.dart';

/// Stage 11's testable core.
///
/// Thin — the interesting Stage 11 decisions are about bytes (which route, what ceiling, what
/// encoding) and live in `core/images/image_downscale.dart` and the screen. What this owns is
/// resolving the profile id and posting.
class SignatureRepository {
  SignatureRepository({
    required SignatureApi api,
    required JourneyRepository journey,
  }) : _api = api,
       _journey = journey;

  final SignatureApi _api;
  final JourneyRepository _journey;

  /// Stores the signature on the profile. Replaces any previous one (the backend upserts).
  Future<void> submit(CapturedSignature signature) async {
    await _api.submit(
      profileId: await _journey.requireProfileId(),
      signature: signature,
    );
  }
}
