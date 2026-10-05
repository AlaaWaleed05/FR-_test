/// Date presentation. Decision D4.2/D4.3 of the 2026-09-07 UI/UX design plan.
///
/// **`DD/MM/YYYY`, Latin digits, `/` separators. Never ISO on any customer or operator screen.**
///
/// **Formatting happens at the presentation boundary ONLY.** Wire types and stored values are
/// unchanged — the backend goes on rendering `LocalDate.toString()`, which is ISO. This is the
/// project's existing asymmetric rule: canonicalise what you send, present differently.
library;

/// Renders an ISO `yyyy-MM-dd` date (optionally carrying a time component) as `DD/MM/YYYY`.
///
/// **One rule: this changes a value ONLY when it recognises an ISO date. Every other input —
/// null, blank, a differently shaped string — comes back exactly as it went in.** A value that
/// arrived in an unexpected shape is still the customer's own registry data, so showing it as it
/// came is right; silently blanking or trimming it would hide a real backend change behind a
/// formatter, and the customer would be asked to verify nothing.
String? formatIsoDate(String? iso) {
  if (iso == null) return null;

  // Accept a bare date or the date half of an ISO datetime; anything else falls through.
  final match = RegExp(r'^(\d{4})-(\d{2})-(\d{2})([T ].*)?$').firstMatch(iso.trim());
  if (match == null) return iso;

  return '${match.group(3)}/${match.group(2)}/${match.group(1)}';
}
