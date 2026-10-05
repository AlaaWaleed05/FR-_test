import 'package:mobile/core/entry/entry_api.dart';
import 'package:mobile/core/entry/entry_models.dart';

/// Hand-written test double, matching this codebase's own stub-not-mock culture (see
/// `FakeReferenceApi`).
class FakeEntryApi implements EntryApi {
  AccountCheckResult? accountCheckResultToServe;
  Object? accountCheckErrorToThrow;
  int checkAccountCallCount = 0;
  String? lastCheckedBranch;
  String? lastCheckedAccountNumber;

  ContactChannelsResult? contactChannelsResultToServe;
  Object? contactChannelsErrorToThrow;
  int submitContactChannelsCallCount = 0;

  // Captured from the last submitContactChannels call — lets a test assert WHAT was actually
  // submitted (which branch/account, whether email was included), not just that a call
  // happened. Added under review, S5-02, second pass (G3/G4): the F5 and F8 fixes had no test
  // proving the submitted values were actually correct.
  String? lastSubmittedBranch;
  String? lastSubmittedAccountNumber;
  String? lastSubmittedEmailAddress;

  @override
  Future<AccountCheckResult> checkAccount(String branch, String accountNumber) async {
    checkAccountCallCount++;
    lastCheckedBranch = branch;
    lastCheckedAccountNumber = accountNumber;
    final error = accountCheckErrorToThrow;
    if (error != null) throw error;
    return accountCheckResultToServe!;
  }

  @override
  Future<ContactChannelsResult> submitContactChannels({
    required String branch,
    required String accountNumber,
    required String phoneNumber,
    required bool sms,
    required bool whatsapp,
    String? emailAddress,
  }) async {
    submitContactChannelsCallCount++;
    lastSubmittedBranch = branch;
    lastSubmittedAccountNumber = accountNumber;
    lastSubmittedEmailAddress = emailAddress;
    final error = contactChannelsErrorToThrow;
    if (error != null) throw error;
    return contactChannelsResultToServe!;
  }

  OtpVerifyResult? verifyChannelResultToServe;
  Object? verifyChannelErrorToThrow;
  int verifyChannelCallCount = 0;
  String? lastVerifiedProfileId;
  String? lastVerifiedChannel;
  String? lastVerifiedCode;

  @override
  Future<OtpVerifyResult> verifyChannel({
    required String profileId,
    required String channel,
    required String code,
  }) async {
    verifyChannelCallCount++;
    lastVerifiedProfileId = profileId;
    lastVerifiedChannel = channel;
    lastVerifiedCode = code;
    final error = verifyChannelErrorToThrow;
    if (error != null) throw error;
    return verifyChannelResultToServe!;
  }

  OtpResendResult? resendChannelResultToServe;
  Object? resendChannelErrorToThrow;
  int resendChannelCallCount = 0;
  String? lastResendedProfileId;
  String? lastResendedChannel;

  /// Distinguishes "no correction supplied" from "a correction was supplied" — BL-101. Stays null
  /// for an ordinary resend, which is itself an assertion worth making.
  String? lastResendedCorrectedEmail;

  @override
  Future<OtpResendResult> resendChannel({
    required String profileId,
    required String channel,
    String? correctedEmailAddress,
  }) async {
    resendChannelCallCount++;
    lastResendedProfileId = profileId;
    lastResendedChannel = channel;
    lastResendedCorrectedEmail = correctedEmailAddress;
    final error = resendChannelErrorToThrow;
    if (error != null) throw error;
    return resendChannelResultToServe!;
  }
}
