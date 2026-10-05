import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_providers.dart';
import '../network/dio_provider.dart';
import 'dio_journey_api.dart';
import 'journey_api.dart';
import 'journey_repository.dart';

/// Riverpod wiring for the Stage 10-12 journey pointer and Stage 12's submit. Plain `Provider`s for
/// dependency injection only, no `StateNotifier` — this codebase's screens hold their own ephemeral
/// state with `setState`.
final journeyApiProvider = Provider<JourneyApi>(
  (ref) => DioJourneyApi(ref.watch(dioProvider)),
);

final journeyRepositoryProvider = Provider<JourneyRepository>(
  (ref) => JourneyRepository(
    api: ref.watch(journeyApiProvider),
    sessionDb: ref.watch(sessionDatabaseProvider),
  ),
);
