import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_providers.dart';
import '../network/dio_provider.dart';
import '../reference/reference_providers.dart';
import 'data_entry_api.dart';
import 'data_entry_repository.dart';
import 'dio_data_entry_api.dart';

final dataEntryApiProvider = Provider<DataEntryApi>((ref) {
  return DioDataEntryApi(ref.watch(dioProvider));
});

final dataEntryRepositoryProvider = Provider<DataEntryRepository>((ref) {
  return DataEntryRepository(
    api: ref.watch(dataEntryApiProvider),
    sessionDb: ref.watch(sessionDatabaseProvider),
    referenceRepository: ref.watch(referenceRepositoryProvider),
  );
});
