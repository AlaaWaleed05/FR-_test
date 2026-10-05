import 'dart:async';

import 'package:mobile/core/identityscan/uqudo_scanner.dart';

/// Stands in for the Uqudo SDK, which cannot run in any automated test on a development machine —
/// the SDK supports `armeabi-v7a`/`arm64-v8a` only and does not function on an x86_64 emulator
/// (customer.md Stage 8's own note).
///
/// This is the seam that makes stages 8-9 testable at all, and equally the reason green gates do
/// not prove a scan works: everything below this line is faked, so what the suite exercises is the
/// Dart wiring around the SDK and never the SDK itself. The live on-device run is what closes that
/// gap.
class FakeUqudoScanner implements UqudoScanner {
  int enrollCallCount = 0;
  Map<String, Object?>? lastEnrollArgs;

  /// The JWS a successful capture returns. A plausible three-segment compact serialization, all
  /// synthetic — no real capture, no real tenant, nothing decodable.
  String jws = 'header.payload.signature';

  /// Set to a [UqudoScanFailure] to simulate a session that produced no JWS.
  Object? errorToThrow;

  /// Set to simulate a native side that answers late, or never (F-1).
  ///
  /// A `Completer` left uncompleted is the only faithful shape for the real defect: the pinned
  /// plugin's `pendingResult` is resolved neither way on a null-Intent `RESULT_CANCELED`, so the
  /// Dart future simply never settles. Leave it uncompleted for the hang; complete it after the
  /// timeout has fired to prove a late answer is dropped rather than retained.
  Completer<String>? pendingEnroll;

  @override
  Future<String> enroll({
    required String accessToken,
    required String sessionId,
    required String nonce,
    required String documentType,
  }) async {
    enrollCallCount++;
    lastEnrollArgs = {
      'accessToken': accessToken,
      'sessionId': sessionId,
      'nonce': nonce,
      'documentType': documentType,
    };
    if (errorToThrow != null) throw errorToThrow!;
    if (pendingEnroll != null) return pendingEnroll!.future;
    return jws;
  }
}
