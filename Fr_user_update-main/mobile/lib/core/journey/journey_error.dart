/// The error contract for journey Stages 10-12 — liveness, signature, submission.
///
/// **One mapper for three stages, not three copies.** S5-13 gave all three controllers the same
/// treatment BL-033/BL-037 gave Stage 8, and they deliberately share codes: `PROFILE_TERMINAL` is
/// emitted by liveness, signature AND submission; `STATE_CONFLICT` by liveness and submission;
/// `LIVENESS_REQUIRED` by signature and submission. Three hand-copied mappers would drift on
/// exactly those overlaps, so the codes and the mapping live here once and the three Dio clients
/// call into them.
///
/// **`core/identityscan/` is deliberately NOT retrofitted onto this.** Its `ScanConflictCode` and
/// `DioIdentityScanApi._mapError` are proven, shipped and covered; rewriting them to share this
/// file would put working code at risk for tidiness. The duplication between the two is one enum
/// and one switch, and it is the cheaper side of that trade.
///
/// **Never parse a bare error body.** A coded error is `application/problem+json`, shaped
/// `{type,title,status,detail,instance,code[,blockedUntil][,blockReason]}` — `blockReason` added to
/// `LIVENESS_BLOCKED` by BL-118 at S8-15 and **read by nothing in this app**, because that item's
/// mobile half was cut from V1. An uncoded one is whatever Spring
/// renders, and that differs by runtime — a live Spring Boot 4.1.0 server returns
/// `{timestamp,status,error,path}` while MockMvc renders an EMPTY body for the same exception. The
/// only safe discriminator is "is there a top-level `code` string", which is what
/// [JourneyCode.fromResponseBody] answers and nothing else does.
library;

import 'package:dio/dio.dart';

import '../entry/entry_models.dart' show BackendUnreachableException;

/// Every `code` the Stage 10-12 controllers emit — twelve distinct strings, plus [unknown].
///
/// Counted from source, not from the docs: `LivenessController.java:137-244` (nine),
/// `SignatureController.java:78-95` (three, two of them shared) and
/// `SubmissionController.java:93-116` (four, all shared). S5-13's own report says "eleven"; the
/// discrepancy is a miscount in that prose, not a missing code — this enum is the source-derived
/// list, and the backend's own controller tests assert every string in it.
///
/// [unknown] exists so a code added to the backend later degrades to a re-sync instead of throwing
/// a `FormatException` at the customer — the same deliberate departure `ScanConflictCode` makes.
enum JourneyCode {
  // ---- shared across stages -------------------------------------------------------------------
  /// The journey is over (submitted/approved/rejected). The app re-runs the launch check.
  /// Emitted by all three stages.
  profileTerminal('PROFILE_TERMINAL'),

  /// The app's idea of where the customer is disagrees with the backend's. Recoverable, and the
  /// recovery is real: re-read `POST /api/v1/submission/current` and land where it says.
  /// Emitted by liveness and submission.
  stateConflict('STATE_CONFLICT'),

  /// Stage 10 has not passed yet. Emitted by signature and submission.
  livenessRequired('LIVENESS_REQUIRED'),

  // ---- Stage 10, liveness ---------------------------------------------------------------------
  /// The 5-attempt budget is spent — a 24-hour block, with `blockedUntil` giving the moment it
  /// lifts.
  ///
  /// **`blockedUntil` may be absent or already elapsed.** The controller omits the member when the
  /// deadline is null (`LivenessController.java:150-153`), and the refusal keys on the profile's
  /// stored state rather than on the block still being live. Both cases are handled where it is
  /// rendered, never in the mapper.
  livenessBlocked('LIVENESS_BLOCKED'),

  /// Face-match already passed for this identity cycle — the customer belongs on Stage 11, not
  /// back on Stage 10.
  livenessAlreadyPassed('LIVENESS_ALREADY_PASSED'),

  /// The accepted identity cycle exists but its reference portrait was purged (S5-06), so liveness
  /// has nothing to match against. The customer must scan again — back to Stage 8.
  rescanRequired('RESCAN_REQUIRED'),

  /// The scan is accepted but the Civil Registry lookup has not completed — Stage 9's pause screen.
  registryPending('REGISTRY_PENDING'),

  /// The face capture is past its 600-second life and Uqudo has deleted the session images. The
  /// capture can no longer be used; it is not a retryable upload.
  artifactExpired('ARTIFACT_EXPIRED'),

  /// The match verified but its audit-trail image could not be fetched, so it cannot be accepted.
  /// Same screen as [artifactExpired], kept distinct on the wire for the same reason BL-033 kept
  /// `IMAGES_UNAVAILABLE` distinct from `ARTIFACT_EXPIRED`.
  auditTrailUnavailable('AUDIT_TRAIL_UNAVAILABLE'),

  /// **A `400`, and it means AN ATTEMPT WAS SPENT.** See [LivenessRejectedException].
  livenessRejected('LIVENESS_REJECTED'),

  // ---- Stage 11, signature --------------------------------------------------------------------
  /// **A `400`.** The signature was refused — wrong capture method, wrong content type, empty, or
  /// over 5 MB. Stage 11 has no attempt budget, so unlike [livenessRejected] this costs nothing.
  signatureRejected('SIGNATURE_REJECTED'),

  // ---- Stage 12, submission -------------------------------------------------------------------
  /// Stage 11 has not been captured yet.
  signatureRequired('SIGNATURE_REQUIRED'),

  /// A `code` this app version does not know. Rendered as a re-sync, never as a guessed screen.
  unknown('');

  const JourneyCode(this.wire);

  /// The exact string the backend puts in the body's `code` member.
  final String wire;

  static JourneyCode fromWire(String value) {
    for (final code in JourneyCode.values) {
      if (code != JourneyCode.unknown && code.wire == value) return code;
    }
    return JourneyCode.unknown;
  }

  /// The top-level `code` member, or `null` when the body does not carry one.
  ///
  /// Deliberately total and defensive: `body` may be a decoded `Map`, a raw `String`, or `null`
  /// depending on runtime and content type, and only the first can carry a code. Anything else
  /// answers `null`, routing the caller to the uncoded arm.
  static String? fromResponseBody(Object? body) {
    if (body is! Map) return null;
    final code = body['code'];
    return code is String && code.isNotEmpty ? code : null;
  }
}

/// A `409` from a Stage 10-12 endpoint, carrying its machine-readable [code].
///
/// [blockedUntil] is populated only for [JourneyCode.livenessBlocked], and even then may be null or
/// already elapsed — see that constant's own doc comment.
class JourneyConflictException implements Exception {
  const JourneyConflictException(this.code, {this.blockedUntil});

  final JourneyCode code;
  final DateTime? blockedUntil;

  @override
  String toString() => 'JourneyConflictException(${code.name})';
}

/// A `400` carrying `code: LIVENESS_REJECTED` — the backend examined the face capture and refused
/// it, and **one liveness attempt is gone**.
///
/// This is why the mapper cannot switch on status alone, and it is exactly the scan-side rule
/// BL-037 established: a coded `400` on a stage that HAS a budget means an attempt was spent, an
/// uncoded `400` means the request was malformed and spent nothing. Verified against source rather
/// than assumed — `LivenessService.java:313-320` and `:349-355` both call `recordFailedAttempt`
/// before rethrowing, and every early return inside `recordFailedAttempt` throws a 409-mapped type
/// instead, so a coded 400 can never mean "not counted".
///
/// Covers both causes — a JWS that fails signature verification and an audit-trail image whose
/// checksum does not match — under one code, because customer.md Stage 10 gives a rejected check a
/// single generic failure screen either way.
class LivenessRejectedException implements Exception {
  const LivenessRejectedException();

  @override
  String toString() => 'LivenessRejectedException(an attempt was spent)';
}

/// A `400` carrying `code: SIGNATURE_REJECTED`.
///
/// **No attempt is spent.** Stage 11 has no budget at all, which `SignatureController.java:70-75`
/// states explicitly. The customer simply re-captures.
class SignatureRejectedException implements Exception {
  const SignatureRejectedException();
}

/// An UNCODED `4xx` — the request was malformed, and on a budgeted stage **nothing was spent**.
class JourneyClientErrorException implements Exception {
  const JourneyClientErrorException();
}

/// A `404` — no profile with this id.
class JourneyProfileNotFoundException implements Exception {
  const JourneyProfileNotFoundException();
}

/// The connection-error types treated as "nothing reached the backend".
///
/// Same set as `DioIdentityScanApi`'s, `unknown` included (a mid-request connection reset on the
/// network conditions this project targets). Stage 10's upload carries a JWS that cost the customer
/// a real attempt, so classifying a dropped connection as anything else would turn a retryable
/// upload into a repeated face check.
const _connectionErrorTypes = {
  DioExceptionType.connectionError,
  DioExceptionType.connectionTimeout,
  DioExceptionType.receiveTimeout,
  DioExceptionType.sendTimeout,
  DioExceptionType.unknown,
};

/// Five arms, in this order. The order is the contract, not a convenience — it is S5-07's own
/// order, kept identical so the two mappers cannot diverge in behaviour even though they are
/// separate code.
///
/// 1. **Connection-class** — nothing reached the backend.
/// 2. **Any 5xx** — including `POST /liveness/token`'s unmapped `500` when Uqudo's own token
///    endpoint is down. customer.md Stage 10 makes that a connectivity failure explicitly **not
///    counted against the budget**, and it is indistinguishable on the wire from any other 500, so
///    it is folded in here and the screen says "no attempt was counted" rather than guessing.
/// 3. **A body carrying a top-level `code`.** This arm precedes the status-only arm below purely
///    because of [JourneyCode.livenessRejected]: a `400` that SPENT an attempt, where an uncoded
///    `400` spent nothing.
/// 4. **No code, by status.**
/// 5. **Anything else** — rethrown untouched rather than flattened into a wrong specific error.
Exception mapJourneyError(DioException e) {
  if (_connectionErrorTypes.contains(e.type)) {
    return BackendUnreachableException(e.message ?? e.type.name);
  }

  final status = e.response?.statusCode;
  if (status != null && status >= 500) {
    return BackendUnreachableException('HTTP $status');
  }

  final body = e.response?.data;
  final wire = JourneyCode.fromResponseBody(body);
  if (wire != null) {
    final code = JourneyCode.fromWire(wire);
    if (status == 400) {
      // Only two 400s are ever coded. Any OTHER coded 400 is a backend change this app version does
      // not know about; treating it as "an attempt was spent" would be a guess in the more alarming
      // direction, so it falls through to the uncoded-400 meaning instead.
      if (code == JourneyCode.livenessRejected) {
        return const LivenessRejectedException();
      }
      if (code == JourneyCode.signatureRejected) {
        return const SignatureRejectedException();
      }
      return const JourneyClientErrorException();
    }
    if (status == 409) {
      final blockedUntil = (body as Map)['blockedUntil'];
      return JourneyConflictException(
        code,
        // May be absent, unparseable, or an instant already in the past — all three are handled
        // where it is rendered, never here.
        blockedUntil: blockedUntil is String
            ? DateTime.tryParse(blockedUntil)
            : null,
      );
    }
  }

  switch (status) {
    case 400:
      return const JourneyClientErrorException();
    case 404:
      return const JourneyProfileNotFoundException();
    case 409:
      // An uncoded 409 from these three controllers should be unreachable — S5-13 gave every
      // conflict a code. Degrade to the re-sync rather than inventing a meaning for it.
      return const JourneyConflictException(JourneyCode.unknown);
    default:
      return e;
  }
}
