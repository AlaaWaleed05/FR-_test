import '../entry/entry_models.dart' show BackendUnreachableException, ProfileAlreadyCompleteException;
import 'data_entry_models.dart';

/// Transport boundary for journey Stages 3-6's four endpoints
/// (`backend/.../dataentry/web/DataEntryController`). Kept as a thin interface, separate from
/// `DataEntryRepository`'s local-persistence/queue logic, mirroring `EntryApi`'s own separation.
///
/// Every method's `@throws` list is identical: [DataEntryRejectedException] (400, no wire detail —
/// see that exception's own doc comment), [ProfileAlreadyCompleteException] (409, reused from
/// `entry_models.dart` — the terminal-profile case is identical in meaning to Stage 1b's), and
/// [BackendUnreachableException]. A 404 (unknown profile) is not modelled as a distinct exception —
/// it should not be reachable through this app's own screens (a profile always exists by the time
/// stage 3 is reachable) and is left to surface as a generic error, same reasoning
/// `UnknownOtpChannelException` used at Stage 2 does NOT extend to here.
abstract class DataEntryApi {
  Future<void> submitStage3({
    required String profileId,
    required String sexDeclared,
    required String ethnicity,
    required String countryOfResidenceCode,
    required String maritalStatus,
    String? spouseName,
    bool? hasChildren,
    int? childrenCount,
    required int educationLevel,
    required String birthCountryCode,
    String? birthStateCode,
    String? birthStateText,
    required String birthCityText,
    int? countryListVersion,
    int? adminDivisionListVersion,
  });

  Future<void> submitStage4({
    required String profileId,
    required String occupationCode,
    required List<IncomeSourceEntry> incomeSources,
    required String monthlyExpensesSdg,
    int? occupationListVersion,
    int? incomeSourceListVersion,
  });

  Future<void> submitStage5({
    required String profileId,
    required String countryCode,
    String? stateCode,
    String? stateText,
    String? localityCode,
    String? localityText,
    required String city,
    required String area,
    required String street,
    required String block,
    required String houseNumber,
    int? countryListVersion,
    int? adminDivisionListVersion,
  });

  Future<void> submitStage6({
    required String profileId,
    required String employer,
    required String countryCode,
    String? stateCode,
    String? stateText,
    String? localityCode,
    String? localityText,
    required String city,
    required String area,
    required String street,
    required String block,
    int? countryListVersion,
    int? adminDivisionListVersion,
    bool? salaryCertificateAttached,
  });

  /// Stage 7 — `{profileId, identityType}`, where `identityType` is `'passport'` or
  /// `'national_id'` (`DataEntryService.IDENTITY_TYPES`). No list version: the two values are a
  /// fixed wire vocabulary, not server-supplied reference data.
  Future<void> submitStage7({required String profileId, required String identityType});

  /// Stage 6's optional salary/income certificate — `POST /api/v1/salary-certificate`
  /// (`salarycertificate.web.SalaryCertificateController`, S4-06/BL-022), which is its own
  /// endpoint rather than a field on `Stage6Request` because it carries bytes.
  ///
  /// **BL-105: nothing in this app called it until S8-14.** The customer was shown «تم إرفاق»
  /// the moment a file was picked, and the file was deleted with the rest of local state when the
  /// session cleared — so the confirmation named an attachment the bank never received.
  ///
  /// [contentType] must be one of `image/png`, `image/jpeg`, `application/pdf`
  /// (`SalaryCertificateService.ALLOWED_CONTENT_TYPES`); the service rejects anything else with a
  /// `400`. Content is base64 of the raw bytes, capped server-side at 10 MB BEFORE encoding.
  Future<void> uploadSalaryCertificate({
    required String profileId,
    required String contentType,
    required String contentBase64,
  });
}
