import 'dart:async';

import 'package:mobile/core/journey/journey_api.dart';
import 'package:mobile/core/journey/journey_models.dart';
import 'package:mobile/core/liveness/liveness_api.dart';
import 'package:mobile/core/liveness/liveness_models.dart';
import 'package:mobile/core/liveness/uqudo_face_scanner.dart';

/// The face-SDK seam's fake. The real SDK cannot run on this machine at all — no x86_64 support —
/// so every automated test drives this instead, and the live device run stays owed.
class FakeUqudoFaceScanner implements UqudoFaceScanner {
  String jwsToReturn = 'header.face.signature';

  /// Set to throw instead of returning.
  Object? errorToThrow;

  /// Set to a completer that is never completed, to simulate the silent-SDK hang.
  Completer<String>? pendingSession;

  int callCount = 0;
  Map<String, String>? lastArgs;

  @override
  Future<String> faceSession({
    required String accessToken,
    required String faceSessionId,
  }) async {
    callCount++;
    lastArgs = {'accessToken': accessToken, 'faceSessionId': faceSessionId};
    final pending = pendingSession;
    if (pending != null) return pending.future;
    final error = errorToThrow;
    if (error != null) throw error;
    return jwsToReturn;
  }
}

class FakeLivenessApi implements LivenessApi {
  FaceTokenIssuance issuanceToServe = FaceTokenIssuance(
    profileId: 'p1',
    accessToken: 'token-never-stored',
    faceSessionId: 'fs-1',
    usableUntil: DateTime.utc(2099),
  );
  FaceResult resultToServe = const FaceResult(
    profileId: 'p1',
    passed: true,
    matchLevel: 5,
  );

  Object? issueTokenError;
  Object? submitResultError;
  Object? reportTerminatedError;

  int issueTokenCallCount = 0;
  int submitResultCallCount = 0;
  int reportTerminatedCallCount = 0;
  Map<String, String?>? lastTerminatedArgs;
  Map<String, String>? lastSubmitArgs;

  @override
  Future<FaceTokenIssuance> issueToken(String profileId) async {
    issueTokenCallCount++;
    final error = issueTokenError;
    if (error != null) throw error;
    return issuanceToServe;
  }

  @override
  Future<FaceResult> submitResult({
    required String profileId,
    required String faceSessionId,
    required String jws,
  }) async {
    submitResultCallCount++;
    lastSubmitArgs = {
      'profileId': profileId,
      'faceSessionId': faceSessionId,
      'jws': jws,
    };
    final error = submitResultError;
    if (error != null) throw error;
    return resultToServe;
  }

  @override
  Future<void> reportTerminated({
    required String profileId,
    required String faceSessionId,
    required String sdkErrorCode,
    String? partialJws,
  }) async {
    reportTerminatedCallCount++;
    lastTerminatedArgs = {
      'profileId': profileId,
      'faceSessionId': faceSessionId,
      'sdkErrorCode': sdkErrorCode,
      'partialJws': partialJws,
    };
    final error = reportTerminatedError;
    if (error != null) throw error;
  }
}

class FakeJourneyApi implements JourneyApi {
  JourneyPointer pointerToServe = const JourneyPointer(
    profileId: 'p1',
    stage: JourneyStage.liveness,
  );
  SubmissionReceipt receiptToServe = const SubmissionReceipt(
    profileId: 'p1',
    referenceNumber: 'REF-0001',
    status: 'submitted',
  );

  Object? currentPointerError;
  Object? submitError;

  int currentPointerCallCount = 0;
  int submitCallCount = 0;

  @override
  Future<JourneyPointer> currentPointer(String profileId) async {
    currentPointerCallCount++;
    final error = currentPointerError;
    if (error != null) throw error;
    return pointerToServe;
  }

  @override
  Future<SubmissionReceipt> submit(String profileId) async {
    submitCallCount++;
    final error = submitError;
    if (error != null) throw error;
    return receiptToServe;
  }
}
