/// Wire models and the error contract for journey Stages 8-9
/// (`backend/.../identityscan/web/IdentityScanController`).
///
/// **The error contract, and why it is shaped this way.** This controller is the only one in the
/// backend that emits a machine-readable `code` (BL-033 for the eight `409`s, BL-037 for the one
/// `400` that spends a retry attempt). Every OTHER endpoint this app calls — including
/// `/api/v1/data-entry/stage7`, which Stage 7's screen uses — still answers with a bare
/// `ResponseStatusException` carrying no code at all. So the mapper in `DioIdentityScanApi` must
/// handle both, and it selects on the PRESENCE of a `code` member rather than on the status alone.
///
/// **Never parse a bare error body.** A coded error is `application/problem+json` and its shape is
/// `{type,title,status,detail,instance,code[,blockedUntil][,blockReason]}` — `blockReason` added by
/// BL-118 at S8-15 and **read by nothing in this app**, see [ScanConflictCode.scanBlocked]. An
/// uncoded one is whatever Spring
/// happens to render, and that differs by runtime: a live Spring Boot 4.1.0 server returns
/// `{timestamp,status,error,path}` (captured live, `IdentityScanController.java:214-218`) while
/// MockMvc renders an EMPTY body for the same exception (`IdentityScanControllerTest.java:195-198`
/// says so outright — "JsonPath cannot be evaluated against one"). Both are true; they are two
/// runtimes, not a contradiction. The only safe discriminator is therefore "is there a top-level
/// `code` string", which is what [ScanConflictCode.fromResponseBody] answers and nothing else.
library;

/// The eight `409` codes `IdentityScanController` emits (BL-033), plus [unknown].
///
/// [unknown] exists so a code added to the backend later degrades to the generic conflict screen
/// instead of throwing a `FormatException` at the customer. That is a deliberate departure from
/// `DioEntryApi`'s "throw on an unrecognised wire value" idiom: an unrecognised *outcome* there is
/// a client bug worth failing loudly on, whereas an unrecognised *error code* is already an error
/// path and crashing it would replace a degraded message with no message.
enum ScanConflictCode {
  /// The journey is over (approved/rejected/submitted/mismatch) — re-run the launch check.
  profileTerminal('PROFILE_TERMINAL'),

  /// Both scan budgets spent — a 24-hour block, with `blockedUntil` giving the moment it lifts.
  ///
  /// **`blockedUntil` can be in the PAST.** The refusal keys on the profile's status, not on the
  /// block still being live, and the reset is lazy — only `issueToken` clears it. A customer whose
  /// 24 hours elapsed but who has not passed back through Stage 8 receives this code with an
  /// elapsed instant. It can also be absent entirely (the controller omits the member when null,
  /// `IdentityScanController.java:241-243`). **The two cases are no longer handled the same way.**
  /// Since S8-14 they are separate cases in `core/widgets/blocked_view.dart` (`BlockedView`, which
  /// replaced `ScanBlockedView`): an ELAPSED instant means the server refused after the wait was
  /// over, and an ABSENT one means no wait was ever announced — so the second must not borrow the
  /// first's «انتهت مدة الانتظار» copy, which would claim something the app never observed.
  ///
  /// **Since S8-15 the body may ALSO carry `blockReason` (BL-118), and this app does not read it.**
  /// The backend half shipped; the mobile half was CUT from V1 by product-owner ruling, because
  /// `BlockedView` is already honest without it. Two things to know before anyone wires it up: an
  /// ABSENT `blockReason` means "the arm that refused does not know why the block exists", never
  /// "not capped" — six server paths report a block they did not apply — and ruling A (BL-114)
  /// still forbids copy that names manual completion or promises a remedy.
  scanBlocked('SCAN_BLOCKED'),

  /// This document type's own budget is spent; the other type still has a fresh one.
  scanTypeExhausted('SCAN_TYPE_EXHAUSTED'),

  /// The scan verified but its images could not be fetched, so it cannot be accepted.
  imagesUnavailable('IMAGES_UNAVAILABLE'),

  /// The JWS is past its 2-hour life and Uqudo has deleted the session images. The capture can no
  /// longer be used — this is the "not a retryable upload" half of customer.md Stage 13's
  /// stale-artifact rule.
  artifactExpired('ARTIFACT_EXPIRED'),

  /// The scan is already accepted and the Civil Registry lookup has not completed — the customer
  /// belongs on Stage 9's pause screen, not on Stage 8.
  registryPending('REGISTRY_PENDING'),

  /// A Stage 9 action was attempted while the registry result is not `ok`. Same pause screen as
  /// [registryPending]; the two stay distinct on the wire because they are raised from different
  /// halves of the journey, and this app keeps that distinction rather than collapsing it.
  registryNotReady('REGISTRY_NOT_READY'),

  /// The app's idea of where the customer is disagrees with the backend's. Recoverable: re-read
  /// Stage 9's payload with `POST /registry-review/current` (S5-11) and land wherever that says.
  stateConflict('STATE_CONFLICT'),

  /// A `code` this app version does not know. Rendered as the generic conflict message.
  unknown('');

  const ScanConflictCode(this.wire);

  /// The exact string the backend puts in the body's `code` member.
  final String wire;

  static ScanConflictCode fromWire(String value) {
    for (final code in ScanConflictCode.values) {
      if (code != ScanConflictCode.unknown && code.wire == value) return code;
    }
    return ScanConflictCode.unknown;
  }

  /// The top-level `code` member, or `null` when the body does not carry one.
  ///
  /// Deliberately total and defensive: `body` may be a decoded `Map`, a raw `String`, or `null`
  /// depending on the runtime and the content type, and only the first can carry a code. Anything
  /// else answers `null`, which routes the caller to the uncoded arm of the mapper. This is the
  /// single place the "absence of a code, never a parsed body" rule is enforced.
  static String? fromResponseBody(Object? body) {
    if (body is! Map) return null;
    final code = body['code'];
    return code is String && code.isNotEmpty ? code : null;
  }
}

/// A `409` from an identity-scan endpoint, carrying BL-033's machine-readable [code].
///
/// [blockedUntil] is populated only for [ScanConflictCode.scanBlocked], and even then may be null
/// (absent from the body) or already elapsed — see that constant's own doc comment.
class ScanConflictException implements Exception {
  const ScanConflictException(this.code, {this.blockedUntil});

  final ScanConflictCode code;
  final DateTime? blockedUntil;

  @override
  String toString() => 'ScanConflictException(${code.name})';
}

/// A `400` carrying `code: SCAN_REJECTED` (BL-037) — the backend examined the scan and refused it,
/// and **one retry attempt is gone**.
///
/// This is the whole point of BL-037 and the reason the mapper cannot switch on status alone: an
/// ordinary malformed-request `400` spends nothing and is [IdentityScanClientErrorException]. The
/// two are indistinguishable without the code, and showing the generic error for this one would
/// silently mislead the customer about their remaining budget.
///
/// It covers both budget-spending causes — a JWS that fails signature verification and a document
/// image whose checksum does not match — under one code, because customer.md Stage 8 gives a
/// rejected scan a single generic failure screen either way.
class ScanRejectedException implements Exception {
  const ScanRejectedException();
}

/// A `400` with NO `code` — the request itself was malformed, and **nothing was spent**.
///
/// Not reachable through this app's own screens if they are correct, which is exactly why it is
/// modelled: reaching it means a client bug, and the screen says so honestly rather than blaming
/// the customer's document.
class IdentityScanClientErrorException implements Exception {
  const IdentityScanClientErrorException();
}

/// A `404` — no such profile. The device and the backend disagree about what exists, so the app
/// re-runs Stage 0's launch check rather than guessing.
class ProfileNotFoundException implements Exception {
  const ProfileNotFoundException();
}

/// What `POST /api/v1/identity-scan/token` returned.
///
/// [accessToken] is a **tenant-scoped** Uqudo bearer token with a ~1800s life, minted fresh by the
/// backend for this one launch (`HttpUqudoClient.issueAccessToken`, deliberately never served from
/// the adapter's cache). It is passed straight to the SDK and dropped. It is never persisted, never
/// logged, and never held beyond the scan it was minted for — customer.md Stage 7's entire reason
/// for requesting it at tap-to-scan rather than at Stage 7's Next.
class TokenIssuance {
  const TokenIssuance({
    required this.profileId,
    required this.accessToken,
    required this.sessionId,
    required this.nonce,
    required this.documentType,
    required this.usableUntil,
  });

  final String profileId;
  final String accessToken;
  final String sessionId;
  final String nonce;
  final String documentType;

  /// When this whole issuance stops working — the server's own answer, never a constant of ours
  /// (BL-114(a), S8-15). On this stage it is the access token's expiry; on the liveness stage the
  /// equivalent field is a DIFFERENT and much earlier deadline, which is why the backend resolves
  /// it rather than letting either client guess.
  ///
  /// **The backend sends this and NOTHING IN THIS APP READS IT YET.** Said plainly because the
  /// alternative is a comment that freezes an old truth and stops the next session checking
  /// (CLAUDE.md; four prior findings). It exists because BL-114(a)'s mobile half — re-launching an
  /// issuance a camera-permission denial left unused, rather than buying a second lifetime mint —
  /// was built at S8-15 and then **deliberately not shipped**: it re-launched the SDK on an
  /// already-used `sessionId`, and `docs/components/uqudo-sdk.md` records Uqudo's own published
  /// rule that a fresh session id is required on every launch. Blocked on `@agent-researcher`; do
  /// not build a reuse path on this field without that pass.
  final DateTime usableUntil;
}

/// Stage 9's display payload — the body of `/scan-result`, `/registry-review/current` and
/// `/registry-review/retry` alike (`ScanDisplayResponse`).
///
/// **Every `registry*` field and [dateOfBirth] is null whenever [registryReady] is false.**
/// [nationalNumber] is NOT nullable — the backend's parser fails closed on a missing identity
/// number (`UqudoJwsParser.java:149-152`), so a payload exists only if a number was read.
/// [availableImageKinds] is always a list and may legitimately be empty (a passport has no
/// `doc_back`).
class ScanDisplay {
  const ScanDisplay({
    required this.profileId,
    required this.cycleId,
    required this.documentType,
    required this.nationalNumber,
    required this.registryReady,
    required this.availableImageKinds,
    this.nameArGiven,
    this.nameArFather,
    this.nameArGrandfather,
    this.nameArGreatGrandfather,
    this.nameArMother,
    this.nameArMotherFather,
    this.nameArMotherGrandfather,
    this.nameArMotherGreatGrandfather,
    this.firstNamesEn,
    this.lastNameEn,
    this.sexRegistry,
    this.dateOfBirth,
    this.rawAddressAr,
  });

  final String profileId;
  final String cycleId;
  final String documentType;
  final String nationalNumber;

  /// False means the Civil Registry lookup has not produced a result yet — a `200`, never an
  /// error (S5-11's product-owner decision: "erroring on a read would make the pause screen
  /// reachable only through a failure"). Drives Stage 9's pause state.
  final bool registryReady;

  final List<String> availableImageKinds;

  final String? nameArGiven;
  final String? nameArFather;
  final String? nameArGrandfather;
  final String? nameArGreatGrandfather;
  final String? nameArMother;
  final String? nameArMotherFather;
  final String? nameArMotherGrandfather;
  final String? nameArMotherGreatGrandfather;
  final String? firstNamesEn;
  final String? lastNameEn;
  final String? sexRegistry;

  /// ISO `uuuu-MM-dd` as the backend renders `LocalDate.toString()`, or null.
  final String? dateOfBirth;

  final String? rawAddressAr;
}

/// The four image kinds `GET /api/v1/identity-scan/image/{kind}` will serve, mirroring the
/// backend's `ScanImageKinds` allowlist. Only the ones named in
/// [ScanDisplay.availableImageKinds] actually exist for a given scan — the screen requests those
/// rather than probing, because every absent case is one indistinguishable `404` by design.
abstract final class ScanImageKinds {
  static const String docFront = 'doc_front';
  static const String docBack = 'doc_back';
  static const String portraitUqudo = 'portrait_uqudo';
  static const String portraitRegistry = 'portrait_registry';
}

/// Stage 7's two identity types, in the journey's own vocabulary — the exact strings
/// `DataEntryService.IDENTITY_TYPES` and `DocumentTypes` accept on the wire.
///
/// **`SDN_ID`/`PASSPORT` are Uqudo's vocabulary and never appear on any wire this app writes.**
/// The translation happens once, inside `PluginUqudoScanner`, at the point the SDK builder is
/// constructed. See `DocumentTypes.java` for the backend's mirror of the same split.
abstract final class IdentityDocumentTypes {
  static const String passport = 'passport';
  static const String nationalId = 'national_id';

  static bool isValid(String value) => value == passport || value == nationalId;
}

/// A scan the SDK returned that the backend has not yet acknowledged — customer.md Stage 8's
/// "the app retains it and retries the upload rather than making the customer rescan" (BL-034).
///
/// **Held in memory only, for the life of the process** (product-owner decision, this session).
/// It is deliberately NOT written to `session.db` or to any file: durable cross-restart retention
/// is a separate decision with its own retention rule, and a JWS is a PII-bearing identity
/// artifact. An app restart legitimately loses this and the customer rescans.
///
/// All five fields are re-sent byte-identically on a retry. That is not optional: the backend
/// re-checks `sessionId`/`nonce` against what it issued BEFORE it reaches the duplicate check
/// (`IdentityScanService.java:304-309`), and the duplicate check itself matches on the JWS's own
/// `jti` (`:350-375`). A retry that changed any of them would be judged a fresh submission.
class RetainedScan {
  const RetainedScan({
    required this.profileId,
    required this.sessionId,
    required this.nonce,
    required this.documentType,
    required this.jws,
  });

  final String profileId;
  final String sessionId;
  final String nonce;
  final String documentType;

  /// The raw JWS compact string, forwarded untouched. **Never decoded on the device** (CLAUDE.md:
  /// verification and parsing are server-side only) and never logged — the spike's own masking
  /// rule, which logs only the length, is the precedent.
  final String jws;
}

/// Process-lifetime holder for [RetainedScan]. One mutable slot behind a Riverpod `Provider`, so
/// it survives screen rebuilds and navigation between Stage 8's internal states while dying with
/// the process.
///
/// Not a `StateNotifier` and not persisted — this codebase's screens are `setState`-based and its
/// only durable store is drift, which this value must never reach.
class RetainedScanStore {
  RetainedScan? _retained;

  RetainedScan? get retained => _retained;

  void keep(RetainedScan scan) => _retained = scan;

  /// Called on every terminal outcome for a capture: accepted, [ScanRejectedException],
  /// [ScanConflictCode.artifactExpired] and [ScanConflictCode.imagesUnavailable]. Anything else
  /// leaves it in place, because anything else is a retryable upload.
  ///
  /// **Abandonment is deliberately not on that list, and is not handled here.** `EntryRepository`
  /// owns abandonment and knows nothing about this store, so a capture can outlive the session it
  /// belongs to for the life of the process. Rather than reach across that boundary, the guard
  /// lives where the stale value would actually be used: `Stage8Screen` re-offers a retained
  /// capture only when its `profileId` and `documentType` still match what is on screen, and
  /// discards it otherwise. Found by `@agent-reviewer` at S5-07.
  void clear() => _retained = null;
}
