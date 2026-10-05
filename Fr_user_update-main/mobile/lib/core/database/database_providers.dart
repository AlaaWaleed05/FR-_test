import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'reference_database.dart';
import 'reference_database_native.dart';
import 'session_database.dart';
import 'session_database_native.dart';

final referenceDatabaseProvider = Provider<ReferenceDatabase>((ref) {
  final db = openReferenceDatabase();
  ref.onDispose(db.close);
  return db;
});

final sessionDatabaseProvider = Provider<SessionDatabase>((ref) {
  final db = openSessionDatabase();
  ref.onDispose(db.close);
  return db;
});
