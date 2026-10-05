import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:uqudosdk_flutter/UqudoIdPlugin.dart';
import 'package:uqudosdk_flutter/uqudosdk_flutter.dart';

import 'uqudo_face_scanner.dart';

/// The real [UqudoFaceScanner], driving `uqudosdk_flutter` 3.10.0's face session.
///
/// The builder sequence and the `PlatformException.code`-is-JSON handling are carried over from the
/// S1-02 device spike (`lib/spike/uqudo_spike_screen.dart`), the only code in this repo that has
/// driven the face session on hardware. **Copied, not imported**, for the same reason
/// `PluginUqudoScanner` copies rather than imports: the spike is a throwaway entrypoint with its own
/// Dio and its own profile-gated endpoints, and importing it would drag 500 untested lines into
/// `lcov` and against the coverage gate.
class PluginUqudoFaceScanner implements UqudoFaceScanner {
  PluginUqudoFaceScanner();

  bool _initialised = false;

  /// `init()` + `setLocale('ar')`, once per process.
  ///
  /// R-002 is live and accepted for the demo: the AAR ships `res/values/` only, with no Arabic
  /// translations, so the SDK's own liveness UI renders in English regardless of this call — and the
  /// face flow alone has 63 `uq_face_*` strings. Same accepted position as the scan flow.
  void _ensureInitialised() {
    if (_initialised) return;
    UqudoIdPlugin.init();
    UqudoIdPlugin.setLocale('ar');
    _initialised = true;
  }

  @override
  Future<String> faceSession({
    required String accessToken,
    required String faceSessionId,
  }) async {
    _ensureInitialised();

    final config =
        (FaceSessionConfigurationBuilder()
              ..setToken(accessToken)
              // The BACKEND's face session id, read back from what it recorded at token issuance
              // (V0044's `pending_face_session_id`). The JWS's `data.sessionId` must equal this, and
              // the backend checks it against its own record rather than against what this request
              // claims. NOTE: `jti` is a DIFFERENT uuid on the face flow — unlike enrolment, where
              // `jti == sessionId`. Binding on `jti` here would fail every time.
              ..setSessionId(faceSessionId)
              // The SDK's internal liveness retry loop. 3 is the SDK default and what S1-02 ran.
              ..setMaxAttempts(3)
              ..setAppearanceMode(AppearanceMode.LIGHT)
              // BL-028 (product-owner decision, 2026-09-04). The only documented route to a signed
              // artifact from a terminated face session: "the SDK will return the partial data together
              // with the SessionStatus object in the data attribute, that will contain the same JWS
              // string that is returned in a successful scenario". Delivered on the FAILURE channel —
              // see `faceSessionPartialJws`.
              ..returnDataForIncompleteSession())
            .build();
    // Deliberately NOT called, the list S1-02 settled on (docs/components/uqudo-sdk.md):
    // enableActiveLiveness, allowClosedEyes, enableAuditTrailImageObfuscation, disableSecureWindow,
    // enableRootedDeviceUsage, setUserIdentifier.
    //
    // `setNonce` is NOT called because the backend mints none for face sessions — see
    // `UqudoFaceScanner.faceSession`.
    //
    // `setMinimumMatchLevel` is NOT called, and this one is a hard rule rather than a default
    // (AD-002a, customer.md Stage 10). Setting it would make the SDK consume a low match as an
    // internal retry and terminate exactly like a liveness failure, with no signed artifact —
    // collapsing the one distinction the fraud signal depends on. It can only ever tighten a gate
    // the SDK already applies internally; leaving it unset is correct. The threshold is enforced
    // server-side from whatever `matchLevel` the JWS carries.

    try {
      return await UqudoIdPlugin.faceSession(config);
    } on PlatformException catch (e) {
      throw UqudoFaceFailure(
        faceSessionStatusCode(e),
        partialJws: faceSessionPartialJws(e),
      );
    }
  }
}

/// The SDK puts a JSON envelope `{code, message, task, data}` into `PlatformException.code` and
/// leaves `.message` null on Android / empty on iOS. **Any mapping written against `.message` is
/// dead code** — the FIB reference matches substrings there that can never appear.
String? faceSessionStatusCode(PlatformException e) {
  final decoded = _decodeEnvelope(e);
  if (decoded == null) {
    // Not the JSON envelope — an older plugin build or a platform-level failure. The raw code is
    // still the most useful thing available and carries no payload.
    return e.code;
  }
  final code = decoded['code'];
  return code is String ? code : null;
}

/// BL-028's mobile half: lift the partial JWS out of the envelope's `data` field.
///
/// **Forwarded raw and never decoded here.** A partial JWS is an identity artifact; only the
/// backend may see inside one (CLAUDE.md's server-side-only rule, which the `../FIB` reference
/// violates and must not be copied from). It is also never logged — the spike's own rule, which
/// logs only a length, is the precedent.
///
/// The scan seam does the opposite and discards `data` entirely, because it does not enable
/// `returnDataForIncompleteSession()`. That asymmetry is deliberate on both sides.
///
/// Returns null when the envelope is absent, `data` is missing, or `data` is not a non-empty
/// string — all of which are ordinary (`returnDataForIncompleteSession` only produces one when the
/// session got at least one facial-recognition attempt in, so a customer who backs out of the
/// first screen legitimately has none).
String? faceSessionPartialJws(PlatformException e) {
  final decoded = _decodeEnvelope(e);
  if (decoded == null) return null;
  final data = decoded['data'];
  if (data is! String) return null;
  final trimmed = data.trim();
  return trimmed.isEmpty ? null : trimmed;
}

Map<String, dynamic>? _decodeEnvelope(PlatformException e) {
  try {
    final decoded = jsonDecode(e.code);
    return decoded is Map<String, dynamic> ? decoded : null;
  } on FormatException {
    return null;
  }
}
