// `Uint8List` comes from drift's own re-export of `dart:typed_data` — importing it directly as
// well trips `unnecessary_import`.
import 'package:drift/drift.dart';

import '../database/session_database.dart';
import 'identity_scan_api.dart';
import 'identity_scan_models.dart';
import 'uqudo_scanner.dart';

/// Stages 8-9's testable core, depending only on [IdentityScanApi] + [UqudoScanner] +
/// [SessionDatabase] + [RetainedScanStore] — mirrors `DataEntryRepository`'s own separation.
///
/// **No offline queue here, unlike stages 3-6.** customer.md Stage 7's closing note is explicit
/// that Stage 7 is the boundary: "Before this point, every stage is offline-capable and freely
/// revisited. After it, each step consumes a real Uqudo operation." A queued scan is a
/// contradiction in terms — the SDK needs the network to upload its capture and the backend needs
/// Uqudo reachable to verify it — so every method here propagates `BackendUnreachableException`
/// to a screen instead of swallowing it into a queue.
///
/// **What it does own is the retained-JWS lifecycle** (BL-034). The clearing rules live here
/// rather than in the screen, because forgetting one of them in a screen would either strand a
/// dead JWS that can never be accepted or discard a live one and force a needless rescan — and
/// there is more than one screen that ends a capture.
class IdentityScanRepository {
  IdentityScanRepository({
    required IdentityScanApi api,
    required UqudoScanner scanner,
    required SessionDatabase sessionDb,
    required RetainedScanStore retainedScans,
    Duration scanTimeout = uqudoScanNoAnswerTimeout,
  }) : _api = api,
       _scanner = scanner,
       _db = sessionDb,
       _retainedScans = retainedScans,
       _scanTimeout = scanTimeout;

  final IdentityScanApi _api;
  final UqudoScanner _scanner;
  final SessionDatabase _db;
  final RetainedScanStore _retainedScans;

  /// Injectable only so a test can prove the timeout without waiting three real minutes; every
  /// production construction takes the default. See [uqudoScanNoAnswerTimeout] for the duration's
  /// reasoning.
  final Duration _scanTimeout;

  /// The capture waiting for an acknowledgement, or null. A screen reads this to decide between
  /// offering "retry the upload" and offering "scan again".
  RetainedScan? get retainedScan => _retainedScans.retained;

  /// Discards a retained capture that can no longer be re-sent as-is.
  ///
  /// The store outlives any one screen (it is process-scoped, which is what makes the retry
  /// survive navigation), so it can outlive the thing it was captured FOR: a customer who abandons
  /// the session and starts another, or who switches document type at Stage 7, would otherwise be
  /// offered a re-send that posts the previous session's `profileId` or the previous document's
  /// JWS. `Stage8Screen` checks both on entry and calls this when they no longer match.
  void discardRetainedScan() => _retainedScans.clear();

  Future<String> requireProfileId() async {
    final progress = await (_db.select(
      _db.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    final id = progress?.profileId;
    if (id == null) {
      throw StateError('no in-progress session (LocalProgress.profileId is unset)');
    }
    return id;
  }

  Future<void> advanceToStage(String stage) {
    return (_db.update(_db.localProgress)..where((t) => t.id.equals(0))).write(
      LocalProgressCompanion(resumeStage: Value(stage)),
    );
  }

  /// One whole scan attempt: mint a token, launch the SDK, retain what it returns, upload it.
  ///
  /// The four failures are deliberately NOT collapsed, because they cost the customer different
  /// things and the caller must tell them apart:
  ///
  /// - the token call failing is a connectivity problem and costs **nothing**
  ///   (customer.md Stage 8: "Token request fails ... not counted against the retry budget");
  /// - [UqudoScanFailure] means a launched session produced no JWS — the caller decides whether to
  ///   spend the attempt via [cancelScan], which is the only thing that actually spends it;
  /// - [UqudoScanNoResponse] means the SDK was launched and never answered either way (F-1). The
  ///   session was still launched, so it costs the same as a cancel;
  /// - a failed upload leaves the JWS retained, so [retryUpload] can finish the job without a
  ///   rescan.
  Future<ScanDisplay> runScan(String documentType) async {
    final profileId = await requireProfileId();

    // Minted at the point of use, never at Stage 7's Next — the token lives ~1800s and a customer
    // who put the phone down after Stage 7 would otherwise arrive here holding a dead one
    // (customer.md Stage 7's whole reasoning). Held in this local only, never stored, never logged.
    final issuance = await _api.issueToken(profileId: profileId, documentType: documentType);

    // The timeout wraps the enrol await and NOTHING ELSE, which is load-bearing rather than
    // stylistic. `Future.timeout` does not cancel the future it wraps, it only stops listening to
    // it — so a wrap placed further out (around this whole method, at the screen's call site) would
    // let a late-arriving JWS still run `keep()` below into the process-scoped store and still fire
    // `_upload` in the background, while the screen had already spent the attempt via `/cancel` and
    // moved on. That leaves an orphaned upload racing a cancelled attempt, and a retained capture
    // that Stage 8's entry guard would happily accept on the next visit — same profileId, same
    // documentType — offering a re-send of a session the backend was told was abandoned. Scoped
    // here, the throw happens before anything is retained and a late JWS is simply dropped.
    final jws = await _scanner
        .enroll(
          accessToken: issuance.accessToken,
          sessionId: issuance.sessionId,
          nonce: issuance.nonce,
          documentType: issuance.documentType,
        )
        .timeout(_scanTimeout, onTimeout: () => throw const UqudoScanNoResponse());

    // Retained BEFORE the upload is attempted. That ordering is the entire BL-034 mechanism: if
    // the acknowledgement is lost, this is what makes the difference between a retry and a rescan.
    _retainedScans.keep(
      RetainedScan(
        profileId: profileId,
        sessionId: issuance.sessionId,
        nonce: issuance.nonce,
        documentType: issuance.documentType,
        jws: jws,
      ),
    );

    return _upload(_retainedScans.retained!);
  }

  /// BL-034's retry — re-post the retained capture unchanged.
  ///
  /// Throws [StateError] if nothing is retained, which is a caller bug: a screen offers this only
  /// when [retainedScan] is non-null.
  Future<ScanDisplay> retryUpload() {
    final retained = _retainedScans.retained;
    if (retained == null) {
      throw StateError('no retained scan to retry');
    }
    return _upload(retained);
  }

  /// The single upload path, shared by the first attempt and every retry so they cannot drift.
  ///
  /// All five fields go back byte-identically. The backend re-checks `sessionId`/`nonce` against
  /// its own record before it reaches the duplicate check, and the duplicate check matches the
  /// JWS's `jti` — so an identical resubmission against an already-accepted, registry-`ok` cycle
  /// returns that cycle's stored payload instead of spending an attempt.
  Future<ScanDisplay> _upload(RetainedScan scan) async {
    try {
      final display = await _api.submitScan(
        profileId: scan.profileId,
        sessionId: scan.sessionId,
        nonce: scan.nonce,
        documentType: scan.documentType,
        jws: scan.jws,
      );
      _retainedScans.clear();
      return display;
    } on ScanRejectedException {
      // Examined and refused, and an attempt is gone. Retrying the same bytes would fail
      // identically and spend another.
      _retainedScans.clear();
      rethrow;
    } on ScanConflictException catch (e) {
      // customer.md Stage 13: the app must "distinguish retrying an upload from this capture can
      // no longer be used". These two codes are that second case — Uqudo deletes the session
      // images when the JWS expires (~2 hours), and a JWS that still verifies perfectly is still
      // unusable without them. Every other conflict leaves the capture retained.
      if (e.code == ScanConflictCode.artifactExpired ||
          e.code == ScanConflictCode.imagesUnavailable) {
        _retainedScans.clear();
      }
      rethrow;
    }
    // Everything else — most importantly BackendUnreachableException — leaves the capture in
    // place, which is what makes the retry possible at all.
  }

  /// Records that a launched SDK session ended without a JWS. **This is what spends the attempt**,
  /// not the SDK failure itself, so a caller that decides no attempt is owed simply does not call
  /// it (see [UqudoScanFailure.isCameraUnavailable]).
  Future<void> cancelScan(String documentType) async {
    await _api.cancelScan(profileId: await requireProfileId(), documentType: documentType);
  }

  /// Stage 9's payload, read from storage — no registry call, no write. Both the forward
  /// navigation from Stage 8 and every resume go through this, so the two paths cannot diverge.
  Future<ScanDisplay> currentReview() async => _api.currentReview(await requireProfileId());

  Future<ScanDisplay> retryRegistryLookup() async =>
      _api.retryRegistryLookup(await requireProfileId());

  Future<void> acceptReview() async => _api.acceptReview(await requireProfileId());

  /// Returns the block deadline when this action's own attempt exhausted the budget, else null.
  Future<DateTime?> reportWrongNumber() async => _api.reportWrongNumber(await requireProfileId());

  Future<void> reportWrongDetails() async => _api.reportWrongDetails(await requireProfileId());

  Future<Uint8List> reviewImage(String kind) async =>
      _api.reviewImage(profileId: await requireProfileId(), kind: kind);
}
