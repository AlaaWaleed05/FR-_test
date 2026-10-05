import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:uqudosdk_flutter/UqudoIdPlugin.dart';
import 'package:uqudosdk_flutter/uqudosdk_flutter.dart';

import 'identity_scan_models.dart';
import 'uqudo_scanner.dart';

/// The real [UqudoScanner], driving `uqudosdk_flutter` 3.10.0.
///
/// The builder sequence, the `PlatformException.code`-is-JSON handling and the never-log-the-token
/// discipline are carried over from the S1-02 device spike (`lib/spike/uqudo_spike_screen.dart`),
/// which is the only code in this repo that has actually driven the SDK on hardware. They are
/// **copied, not imported**: the spike is a throwaway separate entrypoint with its own Dio and its
/// own profile-gated backend endpoints, and importing it would also pull its 500 untested lines
/// into `lcov` and against the coverage gate.
class PluginUqudoScanner implements UqudoScanner {
  PluginUqudoScanner();

  bool _initialised = false;

  /// `init()` + `setLocale('ar')`, once per process.
  ///
  /// The spike also subscribes to `EventChannel('io.uqudo.sdk.id/trace')` before `init()`, because
  /// the plugin only forwards trace events once a sink is attached and those events were what the
  /// spike existed to time. That is diagnostics: it is deliberately not carried into production,
  /// where nothing consumes the trace and the events would only be one more place a partial JWS
  /// could reach a log.
  void _ensureInitialised() {
    if (_initialised) return;
    UqudoIdPlugin.init();
    UqudoIdPlugin.setLocale('ar');
    _initialised = true;
  }

  @override
  Future<String> enroll({
    required String accessToken,
    required String sessionId,
    required String nonce,
    required String documentType,
  }) async {
    _ensureInitialised();

    final document = (DocumentBuilder()
          ..setDocumentType(_uqudoDocumentType(documentType))
          // customer.md Stage 9: "Expiry is not checked — RESOLVED. A genuine but expired document
          // is accepted. Only forgery matters, and detecting it is Uqudo's job." The expiry date is
          // still extracted and stored server-side; it simply gates nothing.
          ..disableExpiryValidation()
          // S8-09, walk comment: the SDK's own "Scan <document> / Fit <document> to the frame /
          // Start" intro is its HelpActivity, and the product owner wants it gone — it adds nothing
          // in front of our own preparation screen and it is the ONLY screen in this journey that
          // draws the uqudo logo (`uq_logo_icon`, in `uq_core_toolbar.xml`, which the camera,
          // progress and output layouts do not include). One flag removes both.
          //
          // Verified against the artifact, not the docs, because CLAUDE.md forbids integrating an
          // SDK from documentation alone. The chain: this call sets `isHelpPageDisabled` on the
          // plugin's Document; `Document.g.dart` serialises it; `UqudoIdPlugin.kt:297-298` calls the
          // native `DocumentBuilder.disableHelpPage()`; and `ScannerActivity`/`CameraFragment` in
          // `sdk-bundle-Uqudo-3.10.0.aar` both branch on `Document.isHelpPageVisible()`, skipping
          // the help destination when it is false.
          //
          // **No test can assert this.** `UqudoScanner` exposes `enroll(...)`, not the builder, so
          // the seam a fake could observe does not carry SDK configuration. Device proof only.
          ..disableHelpPage())
        .build();

    final enrolment = (EnrollmentBuilder()
          ..setToken(accessToken)
          // Both are the BACKEND's values, read back from what it recorded at token issuance
          // (V0040's `pending_scan_session_id`/`pending_scan_nonce`). The JWS's own `jti` must
          // equal this sessionId and its `data.nonce` must equal this nonce, and the backend
          // checks both against its own record rather than against what this request claims.
          ..setSessionId(sessionId)
          ..setNonce(nonce)
          ..setAppearanceMode(AppearanceMode.LIGHT)
          ..add(document))
        .build();
    // Deliberately NOT called, same list the spike settled on (docs/components/uqudo-sdk.md):
    // enableFacialRecognition, allowNonPhysicalDocuments, disableSecureWindow,
    // enableRootedDeviceUsage, enableAgeVerification, returnDataForIncompleteSession.
    // returnDataForIncompleteSession is BL-028's decision and belongs to the FACE session in
    // S5-08, not to this one.

    try {
      return await UqudoIdPlugin.enroll(enrolment);
    } on PlatformException catch (e) {
      throw UqudoScanFailure(_statusCodeOf(e));
    }
  }

  /// The journey's vocabulary to Uqudo's. The mirror of the backend's `DocumentTypes`, and the
  /// only place in this app where `SDN_ID` or `PASSPORT` is named — neither ever goes on a wire
  /// this app writes.
  ///
  /// **`SDN_ID` is unproven.** No Sudanese national ID card was available at S1-02 and none is
  /// coming before finalisation (product-owner decision, 2026-09-04), so the passport path is the
  /// only shape ever exercised end to end and the backend parser's national-ID branch is
  /// `[UNVERIFIED]`. `isEnrollmentSupported(SDN_ID)` answers true, but that is an SDK enum
  /// property, not a tenant licence check. This mapping is written to the documented contract; it
  /// is not evidence that the national-ID path works.
  static DocumentType _uqudoDocumentType(String appDocumentType) {
    switch (appDocumentType) {
      case IdentityDocumentTypes.passport:
        return DocumentType.PASSPORT;
      case IdentityDocumentTypes.nationalId:
        return DocumentType.SDN_ID;
      default:
        throw ArgumentError.value(appDocumentType, 'documentType', 'unknown identity document');
    }
  }

  /// The SDK puts a JSON envelope `{code, message, task, data}` into `PlatformException.code` and
  /// leaves `.message` null on Android / empty on iOS. **Any mapping written against `.message` is
  /// dead code** — the FIB reference matches substrings there that can never appear.
  ///
  /// `data` may carry a partial JWS. It is not read here and not logged: this app does not enable
  /// `returnDataForIncompleteSession` on the enrolment builder, and even if a `data` arrived, a
  /// partial JWS is an identity artifact that only the backend may see.
  static String? _statusCodeOf(PlatformException e) {
    try {
      final decoded = jsonDecode(e.code);
      if (decoded is Map<String, dynamic>) {
        final code = decoded['code'];
        return code is String ? code : null;
      }
      return null;
    } on FormatException {
      // Not the JSON envelope — an older plugin build or a platform-level failure. The raw code is
      // still the most useful thing available and carries no payload.
      return e.code;
    }
  }
}
