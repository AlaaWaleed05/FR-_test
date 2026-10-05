import 'dart:typed_data';

import 'package:mobile/core/identityscan/identity_scan_api.dart';
import 'package:mobile/core/identityscan/identity_scan_models.dart';

/// Hand-written test double, matching this codebase's own stub-not-mock culture (see
/// `FakeDataEntryApi` and `FakeEntryApi`) — neither mockito nor mocktail is a dependency here.
///
/// Every method records what it was called with and how often, and each has a settable error slot
/// so a test can drive one arm of a screen's error ladder without touching the others.
class FakeIdentityScanApi implements IdentityScanApi {
  // --- issueToken ---
  int issueTokenCallCount = 0;
  Map<String, Object?>? lastIssueTokenArgs;
  Object? issueTokenErrorToThrow;
  TokenIssuance issuance = TokenIssuance(
    profileId: 'p1',
    accessToken: 'test-access-token',
    sessionId: 'session-1',
    nonce: 'nonce-1',
    documentType: IdentityDocumentTypes.passport,
    // Any value does: `usableUntil` is decoded by the client and READ BY NOTHING. The retention
    // that would have consumed it (BL-114(a)) was built at S8-15 and reverted — it re-launched
    // the SDK on an already-used sessionId. See `TokenIssuance.usableUntil`.
    usableUntil: DateTime.utc(2099),
  );

  // --- submitScan ---
  int submitScanCallCount = 0;
  Map<String, Object?>? lastSubmitScanArgs;

  /// Every recorded submission, so a BL-034 retry test can prove the second one is byte-identical
  /// to the first rather than merely that a second one happened.
  final List<Map<String, Object?>> submitScanCalls = [];

  /// Thrown once, then cleared — lets a test fail an upload and have the retry succeed, which is
  /// the whole dropped-acknowledgement shape.
  Object? submitScanErrorToThrowOnce;
  Object? submitScanErrorToThrow;

  // --- cancelScan ---
  int cancelScanCallCount = 0;
  Map<String, Object?>? lastCancelScanArgs;

  /// The cancel is the call that actually spends the attempt, so it is also the call that can
  /// discover the budget is now exhausted — a `SCAN_BLOCKED` here is a normal outcome.
  Object? cancelScanErrorToThrow;

  // --- Stage 9 ---
  int currentReviewCallCount = 0;
  int retryRegistryLookupCallCount = 0;
  int acceptReviewCallCount = 0;
  int reportWrongNumberCallCount = 0;
  int reportWrongDetailsCallCount = 0;
  int reviewImageCallCount = 0;
  final List<String> reviewImageKinds = [];

  Object? currentReviewErrorToThrow;
  Object? retryRegistryLookupErrorToThrow;
  Object? acceptReviewErrorToThrow;
  Object? reportWrongNumberErrorToThrow;
  Object? reportWrongDetailsErrorToThrow;

  /// The deadline `wrong-number` reports back — non-null only when that action's own attempt
  /// exhausted the budget.
  DateTime? wrongNumberBlockedUntil;

  ScanDisplay display = const ScanDisplay(
    profileId: 'p1',
    cycleId: 'c1',
    documentType: IdentityDocumentTypes.passport,
    nationalNumber: 'NID-TEST-0001',
    registryReady: true,
    availableImageKinds: [ScanImageKinds.docFront, ScanImageKinds.portraitUqudo],
    nameArGiven: 'محمد',
    nameArFather: 'أحمد',
    nameArGrandfather: 'علي',
    nameArMother: 'فاطمة',
    firstNamesEn: 'MOHAMED AHMED',
    lastNameEn: 'ALI',
    sexRegistry: 'm',
    dateOfBirth: '1990-04-17',
    rawAddressAr: 'الخرطوم، الرياض، مربع ٣',
  );

  /// A real, decodable 1x1 transparent PNG — `Image.memory` in a widget test genuinely decodes
  /// what it is given, so an arbitrary byte list fails with "Invalid image data" rather than
  /// standing in for an image. Synthetic in every sense: no capture, no document, no PII.
  Uint8List imageBytes = Uint8List.fromList(const [
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, //
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
    0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
    0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
    0x42, 0x60, 0x82,
  ]);

  @override
  Future<TokenIssuance> issueToken({
    required String profileId,
    required String documentType,
  }) async {
    issueTokenCallCount++;
    lastIssueTokenArgs = {'profileId': profileId, 'documentType': documentType};
    if (issueTokenErrorToThrow != null) throw issueTokenErrorToThrow!;
    // Echo the requested type back, as the real endpoint does: `TokenResponse.documentType` is
    // derived from the request (`IdentityScanController.token`), and the repository hands the SDK
    // the ECHOED value rather than what it asked for. A fake with a fixed type would quietly make
    // that indistinguishable from a bug.
    return TokenIssuance(
      profileId: profileId,
      accessToken: issuance.accessToken,
      sessionId: issuance.sessionId,
      nonce: issuance.nonce,
      documentType: documentType,
      usableUntil: issuance.usableUntil,
    );
  }

  @override
  Future<ScanDisplay> submitScan({
    required String profileId,
    required String sessionId,
    required String nonce,
    required String documentType,
    required String jws,
  }) async {
    submitScanCallCount++;
    final args = {
      'profileId': profileId,
      'sessionId': sessionId,
      'nonce': nonce,
      'documentType': documentType,
      'jws': jws,
    };
    lastSubmitScanArgs = args;
    submitScanCalls.add(args);
    final once = submitScanErrorToThrowOnce;
    if (once != null) {
      submitScanErrorToThrowOnce = null;
      throw once;
    }
    if (submitScanErrorToThrow != null) throw submitScanErrorToThrow!;
    return display;
  }

  @override
  Future<void> cancelScan({required String profileId, required String documentType}) async {
    cancelScanCallCount++;
    lastCancelScanArgs = {'profileId': profileId, 'documentType': documentType};
    if (cancelScanErrorToThrow != null) throw cancelScanErrorToThrow!;
  }

  @override
  Future<ScanDisplay> currentReview(String profileId) async {
    currentReviewCallCount++;
    if (currentReviewErrorToThrow != null) throw currentReviewErrorToThrow!;
    return display;
  }

  @override
  Future<ScanDisplay> retryRegistryLookup(String profileId) async {
    retryRegistryLookupCallCount++;
    if (retryRegistryLookupErrorToThrow != null) throw retryRegistryLookupErrorToThrow!;
    return display;
  }

  @override
  Future<void> acceptReview(String profileId) async {
    acceptReviewCallCount++;
    if (acceptReviewErrorToThrow != null) throw acceptReviewErrorToThrow!;
  }

  @override
  Future<DateTime?> reportWrongNumber(String profileId) async {
    reportWrongNumberCallCount++;
    if (reportWrongNumberErrorToThrow != null) throw reportWrongNumberErrorToThrow!;
    return wrongNumberBlockedUntil;
  }

  @override
  Future<void> reportWrongDetails(String profileId) async {
    reportWrongDetailsCallCount++;
    if (reportWrongDetailsErrorToThrow != null) throw reportWrongDetailsErrorToThrow!;
  }

  @override
  Future<Uint8List> reviewImage({required String profileId, required String kind}) async {
    reviewImageCallCount++;
    reviewImageKinds.add(kind);
    return imageBytes;
  }
}
