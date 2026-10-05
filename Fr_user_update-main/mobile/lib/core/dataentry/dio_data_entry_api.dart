import 'package:dio/dio.dart';

import '../entry/entry_models.dart' show BackendUnreachableException, ProfileAlreadyCompleteException;
import 'data_entry_api.dart';
import 'data_entry_models.dart';

/// The real [DataEntryApi], backed by `dio`. Same shape as `DioEntryApi`: raw calls, explicit
/// status-code-to-exception mapping, connection-class [DioException]s re-thrown as
/// [BackendUnreachableException] so `DataEntryRepository` can tell "the backend rejected this" from
/// "the backend could not be reached" — the whole offline-queue decision depends on that
/// distinction (see that class's doc comment).
class DioDataEntryApi implements DataEntryApi {
  DioDataEntryApi(this._dio);

  final Dio _dio;

  static const _connectionErrorTypes = {
    DioExceptionType.connectionError,
    DioExceptionType.connectionTimeout,
    DioExceptionType.receiveTimeout,
    DioExceptionType.sendTimeout,
    // Added beyond `DioEntryApi`'s own (otherwise identical) set — found under review, S5-05: a
    // connection reset MID-REQUEST (a dropped wifi handoff, common on the Sudan-market network
    // conditions this project targets) surfaces as `DioExceptionType.unknown`, not one of the
    // four above. `DioEntryApi` covers Stage 1a/1b/2, which are genuinely online-only by design
    // and correctly fail hard on this; stages 3-6 are the offline-capable ones this whole queue
    // exists for, so treating it as anything other than "unreachable" here would block a customer
    // on exactly the network condition this feature is meant to tolerate. Deliberately NOT adding
    // `DioExceptionType.badResponse` (a 5xx) — a persistently erroring endpoint must still surface
    // to the customer, not queue forever.
    DioExceptionType.unknown,
  };

  @override
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
  }) => _post('/api/v1/data-entry/stage3', {
    'profileId': profileId,
    'sexDeclared': sexDeclared,
    'ethnicity': ethnicity,
    'countryOfResidenceCode': countryOfResidenceCode,
    'maritalStatus': maritalStatus,
    'spouseName': spouseName,
    'hasChildren': hasChildren,
    'childrenCount': childrenCount,
    'educationLevel': educationLevel,
    'birthCountryCode': birthCountryCode,
    'birthStateCode': birthStateCode,
    'birthStateText': birthStateText,
    'birthCityText': birthCityText,
    'countryListVersion': countryListVersion,
    'adminDivisionListVersion': adminDivisionListVersion,
  });

  @override
  Future<void> submitStage4({
    required String profileId,
    required String occupationCode,
    required List<IncomeSourceEntry> incomeSources,
    required String monthlyExpensesSdg,
    int? occupationListVersion,
    int? incomeSourceListVersion,
  }) => _post('/api/v1/data-entry/stage4', {
    'profileId': profileId,
    'occupationCode': occupationCode,
    'incomeSources': incomeSources
        .map((s) => {'code': s.code, 'primary': s.primary, 'otherText': s.otherText})
        .toList(),
    'monthlyExpensesSdg': monthlyExpensesSdg,
    'occupationListVersion': occupationListVersion,
    'incomeSourceListVersion': incomeSourceListVersion,
  });

  @override
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
  }) => _post('/api/v1/data-entry/stage5', {
    'profileId': profileId,
    'countryCode': countryCode,
    'stateCode': stateCode,
    'stateText': stateText,
    'localityCode': localityCode,
    'localityText': localityText,
    'city': city,
    'area': area,
    'street': street,
    'block': block,
    'houseNumber': houseNumber,
    'countryListVersion': countryListVersion,
    'adminDivisionListVersion': adminDivisionListVersion,
  });

  @override
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
  }) => _post('/api/v1/data-entry/stage6', {
    'profileId': profileId,
    'employer': employer,
    'countryCode': countryCode,
    'stateCode': stateCode,
    'stateText': stateText,
    'localityCode': localityCode,
    'localityText': localityText,
    'city': city,
    'area': area,
    'street': street,
    'block': block,
    'countryListVersion': countryListVersion,
    'adminDivisionListVersion': adminDivisionListVersion,
    'salaryCertificateAttached': salaryCertificateAttached,
  });

  @override
  Future<void> submitStage7({
    required String profileId,
    required String identityType,
  }) => _post('/api/v1/data-entry/stage7', {
    'profileId': profileId,
    'identityType': identityType,
  });

  @override
  Future<void> uploadSalaryCertificate({
    required String profileId,
    required String contentType,
    required String contentBase64,
  }) => _post('/api/v1/salary-certificate', {
    'profileId': profileId,
    'contentType': contentType,
    'contentBase64': contentBase64,
  });

  Future<void> _post(String path, Map<String, dynamic> data) async {
    try {
      await _dio.post<Map<String, dynamic>>(path, data: data);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Exception _mapError(DioException e) {
    if (_connectionErrorTypes.contains(e.type)) {
      return BackendUnreachableException(e.message ?? e.type.name);
    }
    switch (e.response?.statusCode) {
      case 400:
        return const DataEntryRejectedException();
      case 409:
        return const ProfileAlreadyCompleteException();
      default:
        return e;
    }
  }
}
