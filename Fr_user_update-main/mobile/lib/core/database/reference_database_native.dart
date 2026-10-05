import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'reference_database.dart';

/// Opens the real on-device `ReferenceDatabase` file. Isolated in its own file because
/// `path_provider` is a Flutter plugin that transitively pulls in the Flutter engine
/// (`dart:ui`) — importing it from `reference_database.dart` itself would make that file, and
/// everything that only needs its schema/queries, uncompilable under plain `dart run` (as used
/// by `bin/live_reference_fetch_proof.dart` and, transitively, by anything importing it).
ReferenceDatabase openReferenceDatabase() {
  return ReferenceDatabase(
    LazyDatabase(() async {
      final dir = await getApplicationSupportDirectory();
      final file = File(p.join(dir.path, 'reference.sqlite'));
      return NativeDatabase.createInBackground(file);
    }),
  );
}
