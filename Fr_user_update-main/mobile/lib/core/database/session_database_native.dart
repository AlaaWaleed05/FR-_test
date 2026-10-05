import 'dart:io';

import 'package:drift/drift.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../security/session_key_store_native.dart';
import 'session_database.dart';
import 'session_encryption.dart';

/// Opens the real on-device `SessionDatabase` file, encrypted (S5-03, closes R-025). See
/// `reference_database_native.dart`'s doc comment for why file-opening (which needs
/// `path_provider`, a Flutter plugin) is kept out of `session_database.dart` itself. The actual
/// encryption/key/discard logic lives in `session_encryption.dart`, kept Flutter-free so it can
/// also be driven from tests and from `bin/live_session_encryption_proof.dart`.
SessionDatabase openSessionDatabase() {
  return SessionDatabase(
    LazyDatabase(() async {
      final dir = await getApplicationSupportDirectory();
      final file = File(p.join(dir.path, 'session.sqlite'));
      return openEncryptedSessionExecutor(file, const SecureSessionKeyStore());
    }),
  );
}
