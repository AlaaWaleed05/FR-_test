import 'package:mobile/core/dataentry/data_entry_api.dart';
import 'package:mobile/core/dataentry/data_entry_models.dart';

/// Hand-written test double, matching this codebase's own stub-not-mock culture (see
/// `FakeEntryApi`). One shared `errorToThrow`/call-recording pair per stage, since all four
/// methods have an identical shape.
class FakeDataEntryApi implements DataEntryApi {
  Object? stage3ErrorToThrow;
  int submitStage3CallCount = 0;
  Map<String, dynamic>? lastStage3Args;

  /// Records the order in which stage3 calls actually START and FINISH, e.g.
  /// `['start:عربي', 'finish:عربي']` — used to prove two calls never overlap (S5-05 review: a
  /// background `flushPending()` sweep and the customer's own "Next" could otherwise race the
  /// same stage). Keyed on `ethnicity` since tests distinguish calls by the value they send.
  final List<String> stage3CallOrder = [];

  /// When set, `submitStage3` awaits this before returning — lets a test hold a call "in flight"
  /// to construct a genuine overlap window.
  Future<void>? stage3Gate;

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
  }) async {
    submitStage3CallCount++;
    stage3CallOrder.add('start:$ethnicity');
    final gate = stage3Gate;
    if (gate != null) await gate;
    stage3CallOrder.add('finish:$ethnicity');
    lastStage3Args = {
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
    };
    final error = stage3ErrorToThrow;
    if (error != null) throw error;
  }

  Object? stage4ErrorToThrow;
  int submitStage4CallCount = 0;
  Map<String, dynamic>? lastStage4Args;

  @override
  Future<void> submitStage4({
    required String profileId,
    required String occupationCode,
    required List<IncomeSourceEntry> incomeSources,
    required String monthlyExpensesSdg,
    int? occupationListVersion,
    int? incomeSourceListVersion,
  }) async {
    submitStage4CallCount++;
    lastStage4Args = {
      'profileId': profileId,
      'occupationCode': occupationCode,
      'incomeSources': incomeSources,
      'monthlyExpensesSdg': monthlyExpensesSdg,
      'occupationListVersion': occupationListVersion,
      'incomeSourceListVersion': incomeSourceListVersion,
    };
    final error = stage4ErrorToThrow;
    if (error != null) throw error;
  }

  Object? stage5ErrorToThrow;
  int submitStage5CallCount = 0;
  Map<String, dynamic>? lastStage5Args;

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
  }) async {
    submitStage5CallCount++;
    lastStage5Args = {
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
    };
    final error = stage5ErrorToThrow;
    if (error != null) throw error;
  }

  Object? stage6ErrorToThrow;
  int submitStage6CallCount = 0;
  Map<String, dynamic>? lastStage6Args;

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
  }) async {
    submitStage6CallCount++;
    lastStage6Args = {
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
    };
    final error = stage6ErrorToThrow;
    if (error != null) throw error;
  }

  Object? stage7ErrorToThrow;
  int submitStage7CallCount = 0;
  Map<String, dynamic>? lastStage7Args;

  /// When set, `submitStage7` awaits this before returning — same idiom as [stage3Gate]. Stage 7
  /// needs it since walk comment 4 (2026-09-10) made the card itself the forward action: proving
  /// a second tap cannot submit twice requires holding the first submission in flight.
  Future<void>? stage7Gate;

  @override
  Future<void> submitStage7({required String profileId, required String identityType}) async {
    submitStage7CallCount++;
    lastStage7Args = {'profileId': profileId, 'identityType': identityType};
    final gate = stage7Gate;
    if (gate != null) await gate;
    final error = stage7ErrorToThrow;
    if (error != null) throw error;
  }

  Object? salaryCertificateErrorToThrow;
  int uploadSalaryCertificateCallCount = 0;
  Map<String, dynamic>? lastSalaryCertificateArgs;

  /// Runs INSIDE the upload call, after the repository has read the file and resolved the profile
  /// id but before it writes the acceptance stamp. That window is the only place a test can
  /// isolate the final local write — see the "a failing LOCAL WRITE" test — because closing the
  /// database up front makes `_requireProfileId()` throw first and the write is never reached.
  void Function()? onSalaryCertificateUpload;

  /// Held open by a test that needs an upload in flight while it does something else.
  Future<void>? salaryCertificateGate;

  @override
  Future<void> uploadSalaryCertificate({
    required String profileId,
    required String contentType,
    required String contentBase64,
  }) async {
    uploadSalaryCertificateCallCount++;
    onSalaryCertificateUpload?.call();
    final gate = salaryCertificateGate;
    if (gate != null) await gate;
    lastSalaryCertificateArgs = {
      'profileId': profileId,
      'contentType': contentType,
      'contentBase64': contentBase64,
    };
    final error = salaryCertificateErrorToThrow;
    if (error != null) throw error;
  }
}
