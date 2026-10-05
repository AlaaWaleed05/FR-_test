/// Named constants for the seven server-supplied reference lists (docs/reference/), so list
/// codes aren't scattered as magic strings across the client. Not a substitute for the
/// server-supplied catalogue itself — these are wire identifiers, not a hardcoded list of
/// values (CLAUDE.md's reference-list rule is about item data, not the small, stable set of
/// list *names* the wire contract already fixes).
abstract final class ReferenceListCodes {
  static const occupation = 'occupation';
  static const branch = 'branch';
  static const adminDivision = 'admin_division';
  static const incomeSource = 'income_source';
  static const educationLevel = 'education_level';
  static const rejectionReason = 'rejection_reason';
  static const country = 'country';
}
