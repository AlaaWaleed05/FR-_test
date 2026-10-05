import 'package:drift/drift.dart' hide isNull;
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/session_database.dart';

void main() {
  late SessionDatabase db;

  setUp(() {
    db = SessionDatabase.forTesting();
  });

  tearDown(() async {
    await db.close();
  });

  test('pinVersion then pinnedVersionFor round-trips', () async {
    await db.pinVersion('occupation', 3);
    expect(await db.pinnedVersionFor('occupation'), 3);
  });

  test('pinnedVersionFor returns null for a list that was never pinned', () async {
    expect(await db.pinnedVersionFor('branch'), isNull);
  });

  test('pinning the same list again overwrites the prior pin', () async {
    await db.pinVersion('occupation', 1);
    await db.pinVersion('occupation', 2);
    expect(await db.pinnedVersionFor('occupation'), 2);
  });

  test('clear() removes every pin — the completion/abandonment boundary', () async {
    await db.pinVersion('occupation', 1);
    await db.pinVersion('branch', 1);
    await db.clear();
    expect(await db.pinnedVersionFor('occupation'), isNull);
    expect(await db.pinnedVersionFor('branch'), isNull);
  });

  test('clear() also removes local_draft and local_progress rows', () async {
    await db
        .into(db.localDraft)
        .insertOnConflictUpdate(
          LocalDraftCompanion.insert(
            id: const Value(0),
            accountNumber: const Value('123'),
            updatedAt: DateTime.now(),
          ),
        );
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '2',
            verifiedAccountNumber: '123',
            resumeStage: 'contactChannels',
            updatedAt: DateTime.now(),
          ),
        );

    await db.clear();

    expect(await db.select(db.localDraft).getSingleOrNull(), isNull);
    expect(await db.select(db.localProgress).getSingleOrNull(), isNull);
  });
}
