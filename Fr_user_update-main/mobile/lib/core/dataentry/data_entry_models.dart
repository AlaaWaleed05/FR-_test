/// One entry of Stage 4's income-source multi-select. Mirrors the backend's `IncomeSourceInput`
/// wire shape exactly.
class IncomeSourceEntry {
  const IncomeSourceEntry({required this.code, required this.primary, this.otherText});

  final String code;
  final bool primary;

  /// Only meaningful when [code] is [otherIncomeSourceCode].
  final String? otherText;
}

/// The `income_source` list's free-text item code — a wire identifier mirrored from the backend's
/// `DataEntryService.OTHER_INCOME_CODE` constant, not reference-list data (same category as
/// `ReferenceListCodes`' list-code constants).
const otherIncomeSourceCode = 'OTHER';

/// A `400` from any `/api/v1/data-entry/stage{3,4,5,6}` call. The backend's error body carries no
/// `detail` (`DataEntryController` returns a plain Spring default-error JSON with no message
/// field, same as `ContactChannelsController` — see `ContactChannelsRejectedException`'s own doc
/// comment for the identical reasoning) — an honest generic message, never a guessed specific one.
/// The screens' own client-side validation, transcribed from `DataEntryService`'s rules, is meant
/// to make this mostly unreachable in practice.
class DataEntryRejectedException implements Exception {
  const DataEntryRejectedException();
}
