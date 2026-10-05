// S1-02 Uqudo device spike screen. Throwaway. Talks to the backend's `uqudo-spike` profile on
// the laptop over the LAN and to Uqudo through the SDK; logs every observation both on screen
// and to the backend (`POST /client-log`) so an OOM on a 2 GB device loses nothing.
//
// Masking rule: the access token and the raw JWS are never printed -- only their lengths and
// the JWS `jti`. `PlatformException.code` is a JSON string `{code, message, task, data}` whose
// `data` may carry a partial JWS: only `code`/`task`/`message` are shown here; the backend files
// `data` and logs its shape.
import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:uqudosdk_flutter/UqudoIdPlugin.dart';
import 'package:uqudosdk_flutter/uqudosdk_flutter.dart';

const String _defaultBaseUrl = String.fromEnvironment(
  'SPIKE_API_BASE_URL',
  defaultValue: 'http://localhost:8080',
);
const String _spikeKey = String.fromEnvironment('SPIKE_KEY');
const String _pluginVersion = 'uqudosdk_flutter 3.10.0 (pubspec pin)';

class UqudoSpikeScreen extends StatefulWidget {
  const UqudoSpikeScreen({super.key});

  @override
  State<UqudoSpikeScreen> createState() => _UqudoSpikeScreenState();
}

class _UqudoSpikeScreenState extends State<UqudoSpikeScreen> {
  static const EventChannel _trace = EventChannel('io.uqudo.sdk.id/trace');

  final TextEditingController _baseUrl = TextEditingController(text: _defaultBaseUrl);
  final TextEditingController _key = TextEditingController(text: _spikeKey);
  final List<String> _lines = <String>[];
  final List<Map<String, Object?>> _traceEvents = <Map<String, Object?>>[];
  final ScrollController _scroll = ScrollController();

  StreamSubscription<dynamic>? _traceSub;
  bool _busy = false;
  bool _thresholdOn = false;
  int _threshold = 3;
  bool _sendNonce = false;
  String? _enrolmentSessionId;
  String? _enrolmentJti;
  String? _lastFaceSessionId;
  String? _lastFaceJti;
  int? _firstTraceAt;

  @override
  void initState() {
    super.initState();
    // Subscribe BEFORE init(): the plugin only forwards trace events once a sink is attached,
    // and the init/session-start events are the ones that time the SDK activity coming up.
    _traceSub = _trace.receiveBroadcastStream().listen(
      (dynamic event) {
        final int now = DateTime.now().millisecondsSinceEpoch;
        _firstTraceAt ??= now;
        _traceEvents.add(<String, Object?>{'at': now, 'event': event.toString()});
        _log('trace: $event');
      },
      onError: (Object e) => _log('trace error: $e'),
    );
    UqudoIdPlugin.init();
    UqudoIdPlugin.setLocale('ar');
    _log('init done; setLocale(ar); $_pluginVersion; baseUrl=$_defaultBaseUrl');
  }

  @override
  void dispose() {
    _traceSub?.cancel();
    _baseUrl.dispose();
    _key.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Dio _dio() => Dio(
    BaseOptions(
      baseUrl: _baseUrl.text.trim(),
      headers: <String, Object?>{'X-Spike-Key': _key.text.trim()},
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 120),
    ),
  );

  void _log(String line) {
    final String stamped = '${DateTime.now().toIso8601String().substring(11, 23)} $line';
    if (mounted) {
      setState(() => _lines.add(stamped));
    } else {
      _lines.add(stamped);
    }
    // Fire-and-forget: the backend copy is what survives a process death.
    unawaited(
      _dio()
          .post<void>(
            '/api/v1/spike/uqudo/client-log',
            data: <String, Object?>{
              'sessionId': _enrolmentSessionId,
              'ts': DateTime.now().toIso8601String(),
              'line': stamped,
            },
          )
          .catchError((Object _) => Response<void>(requestOptions: RequestOptions())),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.jumpTo(_scroll.position.maxScrollExtent);
      }
    });
  }

  List<Map<String, Object?>> _takeTrace() {
    final List<Map<String, Object?>> copy = List<Map<String, Object?>>.of(_traceEvents);
    _traceEvents.clear();
    _firstTraceAt = null;
    return copy;
  }

  Future<void> _guard(String what, Future<void> Function() body) async {
    if (_busy) {
      _log('$what ignored: busy');
      return;
    }
    setState(() => _busy = true);
    try {
      await body();
    } catch (e) {
      _log('$what failed: $e');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _ping() => _guard('ping', () async {
    final Response<Map<String, dynamic>> r = await _dio().get<Map<String, dynamic>>(
      '/api/v1/spike/uqudo/ping',
      queryParameters: <String, Object?>{'mint': true},
    );
    _log('ping ${r.statusCode}: ${jsonEncode(r.data)}');
  });

  Future<void> _supported() => _guard('supported', () async {
    for (final DocumentType t in <DocumentType>[DocumentType.SDN_ID, DocumentType.PASSPORT]) {
      final bool ok = await UqudoIdPlugin.isEnrollmentSupported(t);
      _log('isEnrollmentSupported(${t.name}) = $ok  [$_pluginVersion; enum property, not a tenant check]');
    }
  });

  Future<void> _scan(DocumentType type) => _guard('scan', () async {
    final Response<Map<String, dynamic>> s = await _dio().post<Map<String, dynamic>>(
      '/api/v1/spike/uqudo/session',
      data: <String, Object?>{'documentType': type.name},
    );
    final Map<String, dynamic> session = s.data!;
    final String sessionId = session['sessionId'] as String;
    final String nonce = session['nonce'] as String;
    final String token = session['accessToken'] as String;
    _enrolmentSessionId = sessionId;
    _log('session $sessionId nonce=$nonce token len=${token.length}');

    final doc = (DocumentBuilder()
          ..setDocumentType(type)
          ..disableExpiryValidation())
        .build();
    final enrollment = (EnrollmentBuilder()
          ..setToken(token)
          ..setSessionId(sessionId)
          ..setNonce(nonce)
          ..setAppearanceMode(AppearanceMode.LIGHT)
          ..add(doc))
        .build();
    // Deliberately NOT called (docs/components/uqudo-sdk.md): enableFacialRecognition,
    // allowNonPhysicalDocuments, disableSecureWindow, enableRootedDeviceUsage,
    // enableAgeVerification, returnDataForIncompleteSession.

    _takeTrace();
    final int tapAt = DateTime.now().millisecondsSinceEpoch;
    _log('enroll(${type.name}) tap');
    try {
      final String jws = await UqudoIdPlugin.enroll(enrollment);
      final int returnAt = DateTime.now().millisecondsSinceEpoch;
      _log('enroll returned after ${returnAt - tapAt} ms, jws len=${jws.length}');
      final Response<Map<String, dynamic>> r = await _dio().post<Map<String, dynamic>>(
        '/api/v1/spike/uqudo/enrolment',
        data: <String, Object?>{
          'sessionId': sessionId,
          'documentType': type.name,
          'jws': jws,
          'elapsedMs': returnAt - tapAt,
          'tapAt': tapAt,
          'firstTraceAt': _firstTraceAt,
          'returnAt': returnAt,
          'traceEvents': _takeTrace(),
        },
      );
      final Map<String, dynamic> out = r.data!;
      _enrolmentJti = out['jti'] as String?;
      _log(
        'backend: signatureValid=${out['signatureValid']} jti=${out['jti']} '
        'jtiEqualsSessionId=${out['jtiEqualsSessionId']} nonceEchoed=${out['nonceEchoed']} '
        'exp-iat=${out['expMinusIat']} hasExp=${out['hasExp']} '
        'topLevelKeys=${out['topLevelKeys']} dataKeys=${out['dataKeys']}',
      );
      for (final Object? img in (out['images'] as List<dynamic>? ?? <Object?>[])) {
        final Map<String, dynamic> m = img! as Map<String, dynamic>;
        _log(
          'image ${m['key']} status=${m['status']} bytes=${m['bytes']} '
          'checksumKey=${m['checksumKeyName']} match=${m['checksumMatches']}',
        );
      }
      _log(
        'portraitStored=${out['portraitStored']} largeStrings=${(out['largeStrings'] as List<dynamic>?)?.length} '
        'deviceAttestation=${out['deviceAttestationShape'] == 'absent' ? 'absent' : 'present'}',
      );
      if (out['warning'] != null) {
        _log('WARNING: ${out['warning']}');
      }
    } on PlatformException catch (e) {
      final int returnAt = DateTime.now().millisecondsSinceEpoch;
      _log('enroll PlatformException after ${returnAt - tapAt} ms: ${_summarise(e)}');
      await _postError(
        context: 'enrolment',
        sessionId: sessionId,
        e: e,
        tapAt: tapAt,
        returnAt: returnAt,
      );
    }
  });

  Future<void> _face(String runLabel) => _guard('face', () async {
    final String? enrolmentSessionId = _enrolmentSessionId;
    if (enrolmentSessionId == null) {
      _log('face: no enrolment session yet');
      return;
    }
    // Mint the Face Session immediately before the tap -- it lives 10 minutes.
    final Response<Map<String, dynamic>> s = await _dio().post<Map<String, dynamic>>(
      '/api/v1/spike/uqudo/face-session',
      data: <String, Object?>{'enrolmentSessionId': enrolmentSessionId, 'withNonce': _sendNonce},
      options: Options(validateStatus: (_) => true),
    );
    final Map<String, dynamic> fs = s.data ?? <String, dynamic>{};
    final String? faceSessionId = fs['faceSessionId'] as String?;
    final String? token = fs['accessToken'] as String?;
    final String? nonce = fs['nonce'] as String?;
    _log(
      'face-session ${s.statusCode}: uqudoStatus=${fs['uqudoStatus']} faceSessionId=$faceSessionId '
      'portraitBytes=${fs['portraitBytes']} nonce=$nonce token len=${token?.length}',
    );
    if (faceSessionId == null || token == null) {
      _log('face: no face session id -- body=${fs['uqudoBody'] ?? fs['error']}');
      return;
    }
    _lastFaceSessionId = faceSessionId;

    final FaceSessionConfigurationBuilder b = FaceSessionConfigurationBuilder()
      ..setToken(token)
      ..setSessionId(faceSessionId)
      ..setMaxAttempts(3)
      // BL-028 (product-owner decision 2026-09-04): the only documented route to a signed
      // artifact from a terminated face session. The partial JWS arrives in the error object's
      // `data` field and is forwarded raw (never decoded here) -- see _postError.
      ..returnDataForIncompleteSession();
    if (_sendNonce && nonce != null) {
      b.setNonce(nonce);
    }
    final int minimumMatchLevelSet = _thresholdOn ? _threshold : -1;
    if (_thresholdOn) {
      b.setMinimumMatchLevel(_threshold);
    }
    // Deliberately NOT called: enableActiveLiveness, allowClosedEyes,
    // enableAuditTrailImageObfuscation, disableSecureWindow, enableRootedDeviceUsage.
    final config = b.build();

    _takeTrace();
    final int tapAt = DateTime.now().millisecondsSinceEpoch;
    _log('faceSession($runLabel) tap threshold=$minimumMatchLevelSet nonce=$_sendNonce');
    try {
      final String jws = await UqudoIdPlugin.faceSession(config);
      final int returnAt = DateTime.now().millisecondsSinceEpoch;
      _log('faceSession returned after ${returnAt - tapAt} ms, jws len=${jws.length}');
      final Response<Map<String, dynamic>> r = await _dio().post<Map<String, dynamic>>(
        '/api/v1/spike/uqudo/face-result',
        data: <String, Object?>{
          'faceSessionId': faceSessionId,
          'jws': jws,
          'elapsedMs': returnAt - tapAt,
          'tapAt': tapAt,
          'returnAt': returnAt,
          'minimumMatchLevelSet': minimumMatchLevelSet,
          'maxAttempts': 3,
          'expectedNonce': nonce,
          'runLabel': runLabel,
          'traceEvents': _takeTrace(),
        },
      );
      final Map<String, dynamic> out = r.data!;
      _lastFaceJti = out['jti'] as String?;
      _log(
        'backend($runLabel): match=${out['match']} matchLevel=${out['matchLevel']} '
        'signatureValid=${out['signatureValid']} jti=${out['jti']} '
        'jtiEqualsFaceSessionId=${out['jtiEqualsFaceSessionId']} hasExp=${out['hasExp']} '
        'exp-iat=${out['expMinusIat']} nonceEchoed=${out['nonceEchoed']} '
        'dataKeys=${out['dataKeys']} '
        'deviceAttestation=${out['deviceAttestationShape'] == 'absent' ? 'absent' : 'present'}',
      );
      for (final Object? img in (out['images'] as List<dynamic>? ?? <Object?>[])) {
        final Map<String, dynamic> m = img! as Map<String, dynamic>;
        _log('image ${m['key']} status=${m['status']} bytes=${m['bytes']} match=${m['checksumMatches']}');
      }
    } on PlatformException catch (e) {
      final int returnAt = DateTime.now().millisecondsSinceEpoch;
      _log('faceSession PlatformException after ${returnAt - tapAt} ms: ${_summarise(e)}');
      await _postError(
        context: 'face',
        sessionId: faceSessionId,
        e: e,
        tapAt: tapAt,
        returnAt: returnAt,
        runLabel: runLabel,
        minimumMatchLevelSet: minimumMatchLevelSet,
      );
    }
  });

  Future<void> _postError({
    required String context,
    required String sessionId,
    required PlatformException e,
    required int tapAt,
    required int returnAt,
    String? runLabel,
    int minimumMatchLevelSet = -1,
  }) async {
    final Response<Map<String, dynamic>> r = await _dio().post<Map<String, dynamic>>(
      '/api/v1/spike/uqudo/error',
      data: <String, Object?>{
        'context': context,
        'sessionId': sessionId,
        'runLabel': runLabel,
        'platformExceptionCode': e.code,
        'platformExceptionMessage': e.message,
        // BL-028: the partial JWS from returnDataForIncompleteSession(), forwarded untouched
        // (a compact JWS string, or null). Parsing is server-side only (CLAUDE.md hard rule).
        'partialJws': _partialJwsOf(e),
        'elapsedMs': returnAt - tapAt,
        'tapAt': tapAt,
        'returnAt': returnAt,
        'minimumMatchLevelSet': minimumMatchLevelSet,
        'traceEvents': _takeTrace(),
      },
    );
    _log('error filed: code=${r.data?['code']} task=${r.data?['task']} dataPresent=${r.data?['dataPresent']}');
  }

  /// The error object's `data` field when it is a string (the partial JWS), else null. Never
  /// decoded on the device; the length is the only thing the log line reports.
  String? _partialJwsOf(PlatformException e) {
    try {
      final Object? data = (jsonDecode(e.code) as Map<String, dynamic>)['data'];
      return data is String && data.isNotEmpty ? data : null;
    } on FormatException {
      return null;
    }
  }

  /// code/task/message only -- `data` may carry a partial JWS and is left to the backend.
  String _summarise(PlatformException e) {
    try {
      final Map<String, dynamic> m = jsonDecode(e.code) as Map<String, dynamic>;
      return 'code=${m['code']} task=${m['task']} message=${m['message']} dataPresent=${m['data'] != null}';
    } on FormatException {
      return 'code=${e.code} message=${e.message}';
    }
  }

  Future<void> _recheck() => _guard('recheck', () async {
    final String? id = _enrolmentSessionId;
    if (id == null) {
      _log('recheck: no enrolment session');
      return;
    }
    final Response<Map<String, dynamic>> r = await _dio().post<Map<String, dynamic>>(
      '/api/v1/spike/uqudo/recheck/$id',
    );
    _log('recheck: ${jsonEncode(r.data)}');
  });

  Future<void> _purge(String? jti, String what) => _guard('purge', () async {
    if (jti == null) {
      _log('purge $what: no jti known');
      return;
    }
    final Response<Map<String, dynamic>> r = await _dio().delete<Map<String, dynamic>>(
      '/api/v1/spike/uqudo/purge/$jti',
    );
    _log('purge $what: ${jsonEncode(r.data)}');
  });

  void _reset() {
    UqudoIdPlugin.init();
    setState(() => _busy = false);
    _log('reset: init() re-run, busy cleared');
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _lines.join('\n')));
    _log('log copied (${_lines.length} lines)');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('S1-02 Uqudo spike')),
      body: Column(
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
            child: TextField(
              controller: _baseUrl,
              decoration: const InputDecoration(labelText: 'backend base URL', isDense: true),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 4, 8, 0),
            child: TextField(
              controller: _key,
              decoration: const InputDecoration(labelText: 'X-Spike-Key', isDense: true),
            ),
          ),
          Wrap(
            spacing: 6,
            runSpacing: 0,
            children: <Widget>[
              _btn('Ping', _ping),
              _btn('Supported?', _supported),
              _btn('Scan SDN_ID', () => _scan(DocumentType.SDN_ID)),
              _btn('Scan PASSPORT', () => _scan(DocumentType.PASSPORT)),
              _btn('Face C (baseline)', () => _face('C')),
              _btn('Face D (2nd, no thr)', () => _face('D')),
              _btn('Face E (2nd, thr)', () => _face('E')),
              _btn('Recheck', _recheck),
              _btn('Purge enrol jti', () => _purge(_enrolmentJti, 'enrolment')),
              _btn('Purge face jti', () => _purge(_lastFaceJti, 'face')),
              _btn('Copy log', _copy),
              TextButton(onPressed: _reset, child: const Text('Reset')),
            ],
          ),
          Row(
            children: <Widget>[
              const SizedBox(width: 8),
              const Text('threshold'),
              Switch(
                value: _thresholdOn,
                onChanged: _busy ? null : (bool v) => setState(() => _thresholdOn = v),
              ),
              DropdownButton<int>(
                value: _threshold,
                items: <int>[1, 2, 3, 4, 5]
                    .map((int v) => DropdownMenuItem<int>(value: v, child: Text('$v')))
                    .toList(),
                onChanged: _busy ? null : (int? v) => setState(() => _threshold = v ?? 3),
              ),
              const SizedBox(width: 12),
              const Text('nonce'),
              Switch(
                value: _sendNonce,
                onChanged: _busy ? null : (bool v) => setState(() => _sendNonce = v),
              ),
              const Spacer(),
              if (_busy) const Padding(padding: EdgeInsets.all(8), child: Text('busy…')),
            ],
          ),
          const Divider(height: 1),
          Expanded(
            child: ListView.builder(
              controller: _scroll,
              padding: const EdgeInsets.all(8),
              itemCount: _lines.length,
              itemBuilder: (_, int i) => SelectableText(
                _lines[i],
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(4),
            child: Text(
              'enrol=${_enrolmentSessionId ?? '-'} jti=${_enrolmentJti ?? '-'} '
              'face=${_lastFaceSessionId ?? '-'} faceJti=${_lastFaceJti ?? '-'}',
              style: const TextStyle(fontSize: 10),
            ),
          ),
        ],
      ),
    );
  }

  Widget _btn(String label, VoidCallback onPressed) => ElevatedButton(
    onPressed: _busy ? null : onPressed,
    child: Text(label, style: const TextStyle(fontSize: 12)),
  );
}
