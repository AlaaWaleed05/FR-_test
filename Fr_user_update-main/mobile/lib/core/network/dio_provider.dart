import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config/app_config.dart';

final dioProvider = Provider<Dio>((ref) {
  return Dio(
    BaseOptions(
      baseUrl: AppConfig.referenceApiBaseUrl,
      // Without these, a black-holed connection hangs on the platform default rather than
      // failing into the poison/backoff path this client builds — found under review,
      // 2026-09-01. The catalogue fetch is the stage 2 → stage 3 gate (client rule 3); it must
      // fail fast, not hang indefinitely.
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
    ),
  );
});
