import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_providers.dart';
import '../network/dio_provider.dart';
import 'dio_identity_scan_api.dart';
import 'identity_scan_api.dart';
import 'identity_scan_models.dart';
import 'identity_scan_repository.dart';
import 'plugin_uqudo_scanner.dart';
import 'uqudo_scanner.dart';

/// Riverpod wiring for stages 8-9, mirroring `data_entry_providers.dart`: plain `Provider`s for
/// dependency injection only, no `StateNotifier` — this codebase's screens hold their own
/// ephemeral state with `setState` (see `ChannelVerificationScreen`'s doc comment on that
/// convention).
final identityScanApiProvider = Provider<IdentityScanApi>(
  (ref) => DioIdentityScanApi(ref.watch(dioProvider)),
);

/// The SDK seam. Overridden with a fake in every test — the Uqudo SDK cannot run on an x86_64
/// emulator, so this is the one dependency no automated run on a development machine can exercise.
final uqudoScannerProvider = Provider<UqudoScanner>((ref) => PluginUqudoScanner());

/// The retained-JWS holder (BL-034).
///
/// A `Provider` rather than screen state so it outlives Stage 8's internal navigation, and a plain
/// object rather than a drift table so it dies with the process. Persisting it would put a
/// PII-bearing identity artifact in `session.db` under a retention rule nobody has written —
/// deferred by product-owner decision, this session. An app restart legitimately loses the capture
/// and the customer rescans.
final retainedScanStoreProvider = Provider<RetainedScanStore>((ref) => RetainedScanStore());

final identityScanRepositoryProvider = Provider<IdentityScanRepository>(
  (ref) => IdentityScanRepository(
    api: ref.watch(identityScanApiProvider),
    scanner: ref.watch(uqudoScannerProvider),
    sessionDb: ref.watch(sessionDatabaseProvider),
    retainedScans: ref.watch(retainedScanStoreProvider),
  ),
);
