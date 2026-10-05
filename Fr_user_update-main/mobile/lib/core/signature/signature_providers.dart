import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../journey/journey_providers.dart';
import '../network/dio_provider.dart';
import 'dio_signature_api.dart';
import 'signature_api.dart';
import 'signature_repository.dart';

final signatureApiProvider = Provider<SignatureApi>(
  (ref) => DioSignatureApi(ref.watch(dioProvider)),
);

final signatureRepositoryProvider = Provider<SignatureRepository>(
  (ref) => SignatureRepository(
    api: ref.watch(signatureApiProvider),
    journey: ref.watch(journeyRepositoryProvider),
  ),
);
