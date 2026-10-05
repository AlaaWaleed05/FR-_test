import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/liveness/plugin_uqudo_face_scanner.dart';

/// BL-028's mobile half, tested directly.
///
/// **Why this file exists when the Stage 8 equivalent has none.** `PluginUqudoScanner`'s envelope
/// handling was left untested at S5-07 on the grounds that the SDK is not exercisable on this
/// machine — true of the SDK, but the envelope parsing itself is pure string work with no platform
/// channel in it, and here it is the whole of what BL-028 asks the app to do. Getting it wrong
/// means either dropping the one artifact a terminated session can yield, or forwarding something
/// that is not a JWS. Neither would be caught by the coverage gate: R-009 records that a file no
/// test imports is not counted at all.
void main() {
  /// The shape the SDK actually produces, per `docs/components/uqudo-sdk.md` and the S1-02 spike:
  /// a JSON envelope stuffed into `PlatformException.code`, with `.message` dead.
  PlatformException envelope(String json) => PlatformException(code: json);

  group('status code', () {
    test('reads `code` out of the JSON envelope', () {
      expect(
        faceSessionStatusCode(
          envelope(
            '{"code":"USER_CANCEL","message":"x","task":"face","data":null}',
          ),
        ),
        'USER_CANCEL',
      );
    });

    test('falls back to the raw code when the envelope is not JSON at all', () {
      // An older plugin build or a platform-level failure. The raw code is still the most useful
      // thing available and carries no payload.
      expect(
        faceSessionStatusCode(PlatformException(code: 'PLATFORM_ERROR')),
        'PLATFORM_ERROR',
      );
    });

    test(
      'answers null when the envelope parses but carries no string code',
      () {
        expect(faceSessionStatusCode(envelope('{"code":42}')), isNull);
      },
    );

    test('never reads `.message`, which is dead on both platforms', () {
      // The FIB reference matches substrings on `.message`; those matches can never fire. A
      // mapping written against it would be dead code, so this asserts the opposite is true here.
      expect(
        faceSessionStatusCode(
          PlatformException(
            code: '{"code":"SESSION_EXPIRED"}',
            message: 'USER_CANCEL',
          ),
        ),
        'SESSION_EXPIRED',
      );
    });
  });

  group('partial JWS', () {
    test('lifts `data` out of the envelope, byte-identically', () {
      // The one thing this must not do is alter it. The device never decodes a JWS.
      const jws = 'eyJhbGciOiJSUzI1NiJ9.eyJkYXRhIjp7fX0.sig';
      expect(
        faceSessionPartialJws(
          envelope(
            '{"code":"SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS",'
            '"message":null,"task":"face","data":"$jws"}',
          ),
        ),
        jws,
      );
    });

    test('answers null when the session produced none — the ordinary case', () {
      // `returnDataForIncompleteSession` yields an artifact only when the session got at least one
      // facial-recognition attempt in, so a customer who backs out of the first screen legitimately
      // has none. That is not an error.
      expect(
        faceSessionPartialJws(envelope('{"code":"USER_CANCEL","data":null}')),
        isNull,
      );
      expect(faceSessionPartialJws(envelope('{"code":"USER_CANCEL"}')), isNull);
    });

    test('answers null for a blank or whitespace-only data field', () {
      // The backend treats absent and blank identically, but sending `"   "` would still spend a
      // request field on nothing and read as an artifact in a log.
      expect(
        faceSessionPartialJws(envelope('{"code":"USER_CANCEL","data":""}')),
        isNull,
      );
      expect(
        faceSessionPartialJws(envelope('{"code":"USER_CANCEL","data":"   "}')),
        isNull,
      );
    });

    test('answers null when `data` is not a string', () {
      expect(
        faceSessionPartialJws(
          envelope('{"code":"USER_CANCEL","data":{"jws":"x"}}'),
        ),
        isNull,
      );
    });

    test('answers null when there is no envelope to read', () {
      expect(
        faceSessionPartialJws(PlatformException(code: 'PLATFORM_ERROR')),
        isNull,
      );
    });
  });
}
