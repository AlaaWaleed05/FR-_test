/// What happened to one list during a `ReferenceRepository.syncCatalog()` call.
enum ListSyncOutcome {
  /// Already cached at the manifest's exact `(version, contentHash)` — not re-fetched.
  upToDate,

  /// Verified and written to the cache; this was the list's first-ever cached version, so it
  /// activated immediately.
  verifiedAndActivated,

  /// Verified and written to the cache, but staged only — an older version stays active until
  /// `activateStagedVersion` is called (AD-002f's staging requirement).
  verifiedAndStaged,

  /// The received bytes' SHA-256 did not match the manifest's `contentHash`. Discarded entirely;
  /// the cache is unchanged. Never "log and continue." Counts toward the two-failure poison
  /// threshold — this is a verification failure, the one client rule 5 is about.
  hashMismatch,

  /// The bytes hash-verified but failed to parse as the expected document shape (a server-side
  /// bug: a verified-but-malformed document). Counts toward poisoning for the same reason as a
  /// hash mismatch — the artifact itself is bad, not the transport.
  malformedDocument,

  /// This EXACT `(listCode, version)` is already cached under a DIFFERENT `contentHash` — the
  /// server republishing a version it already declared immutable (AD-002f). Refused outright
  /// rather than fetched and absorbed: accepting it would mean silently rewriting a version's
  /// content (and possibly its item set) out from under an in-progress session, exactly what
  /// AD-002f's staging model exists to prevent, and there is no way to "stage" a same-key
  /// conflict. Counts toward poisoning — the server, not the transport, is the thing at fault.
  immutabilityViolation,

  /// This `(listCode, version)` failed verification twice before and is not being retried.
  poisoned,

  /// This `(listCode, version)` failed recently and bounded backoff has not yet elapsed. NOT a
  /// failure in itself — it is deliberate pacing, not evidence of a defect — but the list is
  /// still not cached, so it is not a success either.
  backingOff,

  /// The HTTP fetch itself failed (no bytes to verify). Transient by nature (a dropped
  /// connection, a 5xx) — does NOT count toward poisoning, only toward backoff pacing, so a
  /// flaky network cannot permanently strand a list the way two genuine verification failures
  /// are meant to.
  networkError,
}

class ListSyncResult {
  const ListSyncResult({required this.listCode, required this.version, required this.outcome});

  final String listCode;
  final int version;
  final ListSyncOutcome outcome;

  bool get isFailure =>
      outcome == ListSyncOutcome.hashMismatch ||
      outcome == ListSyncOutcome.malformedDocument ||
      outcome == ListSyncOutcome.immutabilityViolation ||
      outcome == ListSyncOutcome.networkError ||
      outcome == ListSyncOutcome.poisoned;
}

class ReferenceSyncResult {
  const ReferenceSyncResult(this.results);

  final List<ListSyncResult> results;

  bool get allSucceeded => results.every((r) => !r.isFailure);

  /// The result for one specific list, or `null` if that list was not part of this sync (e.g.
  /// the manifest hadn't changed — see `ReferenceRepository.syncCatalog`'s 304 handling).
  ListSyncResult? forList(String listCode) {
    for (final r in results) {
      if (r.listCode == listCode) return r;
    }
    return null;
  }
}
