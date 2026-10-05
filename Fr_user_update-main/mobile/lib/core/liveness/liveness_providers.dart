import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../journey/journey_providers.dart';
import '../network/dio_provider.dart';
import 'dio_liveness_api.dart';
import 'liveness_api.dart';
import 'liveness_models.dart';
import 'liveness_repository.dart';
import 'plugin_uqudo_face_scanner.dart';
import 'uqudo_face_scanner.dart';

final livenessApiProvider = Provider<LivenessApi>(
  (ref) => DioLivenessApi(ref.watch(dioProvider)),
);

/// The face-SDK seam. Overridden with a fake in every test — the Uqudo SDK cannot run on an x86_64
/// emulator, so this is the one dependency no automated run on a development machine can exercise.
final uqudoFaceScannerProvider = Provider<UqudoFaceScanner>(
  (ref) => PluginUqudoFaceScanner(),
);

/// The retained face-JWS holder.
///
/// A `Provider` rather than screen state so it outlives Stage 10's internal navigation, and a plain
/// object rather than a drift table so it dies with the process. Persisting it would put biometric
/// material in `session.sqlite` under a retention rule nobody has written.
final retainedFaceStoreProvider = Provider<RetainedFaceStore>(
  (ref) => RetainedFaceStore(),
);

final livenessRepositoryProvider = Provider<LivenessRepository>(
  (ref) => LivenessRepository(
    api: ref.watch(livenessApiProvider),
    scanner: ref.watch(uqudoFaceScannerProvider),
    journey: ref.watch(journeyRepositoryProvider),
    retainedFaces: ref.watch(retainedFaceStoreProvider),
  ),
);
