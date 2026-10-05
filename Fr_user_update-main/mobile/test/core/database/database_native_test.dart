import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/reference_database_native.dart';
import 'package:mobile/core/database/session_database_native.dart';
import 'package:path_provider_platform_interface/path_provider_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

/// Stands in for the real platform channel `path_provider` normally talks to — proves
/// `openReferenceDatabase()`/`openSessionDatabase()` actually resolve a real on-disk file at a
/// real path, the one thing `ReferenceDatabase.forTesting()`/`SessionDatabase.forTesting()`
/// (used everywhere else in this test suite) deliberately skip.
class _FakePathProviderPlatform extends PathProviderPlatform with MockPlatformInterfaceMixin {
  _FakePathProviderPlatform(this.path);
  final String path;

  @override
  Future<String?> getApplicationSupportPath() async => path;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('fru_mobile_db_test_');
    PathProviderPlatform.instance = _FakePathProviderPlatform(tempDir.path);
    // Stands in for the real Keychain/Keystore platform channel `SecureSessionKeyStore`
    // (session_key_store_native.dart) talks to — flutter_secure_storage's own first-party test
    // double, starting with no key stored (matching a fresh device).
    FlutterSecureStorage.setMockInitialValues({});
  });

  tearDown(() {
    tempDir.deleteSync(recursive: true);
  });

  test('openReferenceDatabase creates a real file and is queryable', () async {
    final db = openReferenceDatabase();
    final rows = await db.select(db.referenceLists).get();
    expect(rows, isEmpty); // schema created, nothing cached yet
    await db.close();

    expect(File('${tempDir.path}/reference.sqlite').existsSync(), isTrue);
  });

  test('openSessionDatabase creates a real file and round-trips a pin', () async {
    final db = openSessionDatabase();
    await db.pinVersion('occupation', 1);
    expect(await db.pinnedVersionFor('occupation'), 1);
    await db.close();

    expect(File('${tempDir.path}/session.sqlite').existsSync(), isTrue);
  });
}
