import 'dart:typed_data';

import '../entry/entry_models.dart' show BackendUnreachableException;
import 'identity_scan_models.dart';

/// Transport boundary for journey Stages 8-9's endpoints
/// (`backend/.../identityscan/web/IdentityScanController`). Thin interface, separate from
/// `IdentityScanRepository`'s orchestration, mirroring `DataEntryApi`'s own separation.
///
/// **Every method can throw the same five.** Rather than repeating the list nine times:
///
/// - [ScanConflictException] — a `409` carrying one of BL-033's eight codes. The code, not the
///   status, selects the screen.
/// - [ScanRejectedException] — a `400` with `code: SCAN_REJECTED`; the scan was refused and **one
///   attempt is gone**. Only [submitScan] can raise it in practice.
/// - [IdentityScanClientErrorException] — a `400` with no code; malformed request, nothing spent.
/// - [ProfileNotFoundException] — a `404`.
/// - [BackendUnreachableException] — connection-class failures **and any 5xx**. The 5xx arm
///   matters here in a way it does not for the data-entry endpoints: `POST /token` answers an
///   unmapped `500` when Uqudo's own token endpoint is down (`HttpUqudoClient.mintToken` throws
///   `IllegalStateException` and nothing catches it), and customer.md Stage 8 classifies a token
///   failure as a connectivity failure **not counted against the retry budget**. Folding it into
///   this exception is what lets the screen say so.
///
/// Stage 7 does NOT go through here — its `POST /api/v1/data-entry/stage7` belongs to
/// `DataEntryApi`, because identity type is customer-entered, device-owned data that drafts and
/// queues exactly like stages 3-6 (customer.md Stage 13's ownership table).
abstract class IdentityScanApi {
  /// Mints a fresh, single-use Uqudo access token plus the backend's own `sessionId`/`nonce` for
  /// this launch. Called at the moment the customer taps to scan, never at Stage 7's Next —
  /// customer.md Stage 7 ("No Uqudo token is requested here") and its 1800s-lifetime reasoning.
  Future<TokenIssuance> issueToken({required String profileId, required String documentType});

  /// Posts the SDK's JWS **untouched**. Re-posting an identical five-field submission after a lost
  /// acknowledgement is idempotent server-side (BL-034) and returns the stored payload rather than
  /// spending a fresh attempt — which is what makes [RetainedScanStore] worth having.
  Future<ScanDisplay> submitScan({
    required String profileId,
    required String sessionId,
    required String nonce,
    required String documentType,
    required String jws,
  });

  /// Records that a launched SDK session ended without a JWS. **Spends a retry attempt** —
  /// customer.md Stage 8: "a launched SDK session consumes a real Uqudo operation whether or not a
  /// document was captured, so a cancel is not free".
  Future<void> cancelScan({required String profileId, required String documentType});

  /// Stage 9's payload, read from storage. **No Civil Registry call and no write** (S5-11) — this
  /// is the endpoint a resume uses, and the one `STATE_CONFLICT`'s "re-sync" advice points at.
  Future<ScanDisplay> currentReview(String profileId);

  /// Re-runs the Civil Registry lookup after a pause. Distinct from [currentReview]: this one
  /// writes.
  Future<ScanDisplay> retryRegistryLookup(String profileId);

  /// Stage 9's "Accept" — the customer confirms the registry data is theirs.
  Future<void> acceptReview(String profileId);

  /// Stage 9's "the national number is wrong" — supersedes the cycle and **spends a Stage 8
  /// attempt**, because a misread number is a scanning problem.
  ///
  /// Returns the block deadline when that attempt happened to exhaust the budget, else null
  /// (`WrongNumberResponse.blockedUntil`, nullable by design).
  Future<DateTime?> reportWrongNumber(String profileId);

  /// Stage 9's "the number is right but my details are wrong" — terminal, directed to a branch.
  Future<void> reportWrongDetails(String profileId);

  /// One Stage 9 review image as raw bytes. `kind` is one of [ScanImageKinds], and only the kinds
  /// named in [ScanDisplay.availableImageKinds] are worth requesting — every absent case is one
  /// indistinguishable `404`, deliberately, so probing tells the caller nothing.
  Future<Uint8List> reviewImage({required String profileId, required String kind});
}
