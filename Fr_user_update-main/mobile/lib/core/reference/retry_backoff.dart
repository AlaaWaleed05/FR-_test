/// Pure policy functions implementing docs/components/reference-data.md client rule 5: "hash
/// mismatch: discard, keep the last verified version, bounded backoff, poison the version after
/// two failures, report it." No drift, no dio, no clock — a plain unit test exercises every
/// branch with no setup.
library;

/// How long to wait before the next attempt, given how many consecutive failures a
/// `(listCode, version)` has already accumulated. Bounded: capped at [_maxBackoffSeconds] rather
/// than growing without limit.
///
/// Clamped BEFORE shifting, not after: `1 << (priorFailureCount - 1)` overflows Dart's 64-bit
/// int at `priorFailureCount` ≈ 61, at which point `seconds` wraps to a small or negative value
/// and the `> _maxBackoffSeconds` cap silently stops applying — found under review, 2026-09-01.
/// `totalFailureCount` (unlike the poison-gated `verificationFailureCount`) is genuinely
/// unbounded: a persistently-failing document fetch that never hash-mismatches (a steady 404 on
/// the list endpoint, say) never poisons and keeps incrementing it forever.
Duration backoffFor(int priorFailureCount) {
  if (priorFailureCount <= 0) return Duration.zero;
  if (priorFailureCount >= _capReachedAtFailureCount) {
    return const Duration(seconds: _maxBackoffSeconds);
  }
  return Duration(seconds: 15 * (1 << (priorFailureCount - 1))); // 15s, 30s, 60s, 120s
}

const int _maxBackoffSeconds = 120;

/// `15 * 2^(n-1) == 120` at `n == 4`; beyond this the raw formula would only repeat the same
/// capped value anyway, so clamping here is exact, not approximate.
const int _capReachedAtFailureCount = 4;

/// A version is poisoned once it has failed verification this many times in a row.
const int poisonThreshold = 2;

bool isPoisonedAfter(int failureCount) => failureCount >= poisonThreshold;
