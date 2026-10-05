import 'entry_models.dart';

/// Transport boundary for journey Stage 1a/1b's two endpoints
/// (`backend/.../accountcheck/web/AccountCheckController`,
/// `backend/.../contactchannels/web/ContactChannelsController`). Kept as a thin interface,
/// separate from `EntryRepository`'s local-persistence/resume logic, so that logic is
/// unit-testable with a hand-written fake — mirrors `ReferenceApi`'s own separation.
abstract class EntryApi {
  /// `POST /api/v1/account-check`. Never throws for a business outcome (invalid/inactive/active
  /// are all normal `200` responses) — only for a malformed request (shouldn't happen from this
  /// client) or [BackendUnreachableException].
  Future<AccountCheckResult> checkAccount(String branch, String accountNumber);

  /// `POST /api/v1/contact-channels`.
  ///
  /// @throws ContactChannelsRejectedException 400 — a validation rule was violated server-side
  ///     (no distinguishable cause on the wire — see that exception's own doc comment)
  /// @throws ProfileAlreadyCompleteException 409 — the account's profile is already terminal
  /// @throws SessionTemporarilyBlockedException 429 — Stage 2's phone lock is still active
  /// @throws BackendUnreachableException the backend could not be reached at all
  Future<ContactChannelsResult> submitContactChannels({
    required String branch,
    required String accountNumber,
    required String phoneNumber,
    required bool sms,
    required bool whatsapp,
    String? emailAddress,
  });

  /// `POST /api/v1/otp/verify` (`otpverification/web/OtpVerificationController`). `200` always for
  /// a business outcome (verified/wrong code/expired/locked all decode into [OtpVerifyResult]).
  ///
  /// @throws UnknownOtpChannelException 400 — no challenge exists for this profile/channel pair
  /// @throws BackendUnreachableException the backend could not be reached at all
  Future<OtpVerifyResult> verifyChannel({
    required String profileId,
    required String channel,
    required String code,
  });

  /// `POST /api/v1/otp/resend`. Same `200`-carries-outcome / `400`-for-unknown-channel shape as
  /// [verifyChannel].
  ///
  /// [correctedEmailAddress] carries the customer's in-place fix for a mistyped address
  /// (customer.md Stage 2 "Corrections"; backend half S4-06/BL-012, mobile half BL-101). It is
  /// valid ONLY alongside `channel == 'email'` — the backend answers `400`
  /// (`EmailCorrectionNotApplicableException`) for any other channel, because changing the phone
  /// number is a Stage 1b re-entry, not a resend. Omitted from the request body entirely when
  /// null, so an ordinary resend's wire payload is unchanged.
  ///
  /// **The correction lands even when the resend itself is refused.** The backend applies it
  /// inside the same Phase-1 transaction that decides the reservation, before that outcome is
  /// known, and invalidates the old address's live challenge there — so a `TOO_SOON` or
  /// `CAP_EXHAUSTED` answer still means the address was changed and the old code is dead. Callers
  /// must not report "a code was sent" on those outcomes; see `ChannelVerificationScreen`.
  Future<OtpResendResult> resendChannel({
    required String profileId,
    required String channel,
    String? correctedEmailAddress,
  });
}
