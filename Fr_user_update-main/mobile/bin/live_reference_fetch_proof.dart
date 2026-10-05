// Live two-tier proof for S5-01: fetches, SHA-256-verifies and caches all seven reference lists
// against a REAL running backend, reusing the exact same DioReferenceApi/ReferenceRepository/
// ReferenceDatabase code the app uses. Run with:
//   fvm dart run bin/live_reference_fetch_proof.dart --base-url=http://localhost:8080
//
// Lives under bin/ so it sits outside `flutter test`'s default `test/` target and
// `tool/check_coverage.dart`'s scan — no gate configuration changes needed to keep the normal
// test run hermetic.

import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/network/dio_reference_api.dart';
import 'package:mobile/core/reference/reference_repository.dart';
import 'package:mobile/core/reference/reference_sync_result.dart';

Future<void> main(List<String> args) async {
  final baseUrlArg = args.firstWhere(
    (a) => a.startsWith('--base-url='),
    orElse: () => '--base-url=http://localhost:8080',
  );
  final baseUrl = baseUrlArg.substring('--base-url='.length);

  stdout.writeln('Fetching reference catalogue from $baseUrl ...');

  final dio = Dio(BaseOptions(baseUrl: baseUrl));
  final api = DioReferenceApi(dio);
  final db = ReferenceDatabase.forTesting();
  final repository = ReferenceRepository(api: api, db: db);

  final ReferenceSyncResult result;
  try {
    result = await repository.syncCatalog();
  } catch (e, stackTrace) {
    stderr.writeln('FAILED: could not reach backend at $baseUrl: $e\n$stackTrace');
    exit(1);
  }

  var allOk = true;
  for (final r in result.results) {
    final row = await (db.select(
      db.referenceLists,
    )..where((t) => t.listCode.equals(r.listCode) & t.version.equals(r.version))).getSingleOrNull();

    // Count the ACTUAL cached item rows, not the manifest's claimed itemCount the list row
    // merely copies — a document that hash-verifies but writes fewer rows than it claims (e.g.
    // a duplicate itemCode silently collapsing under the upsert) would otherwise still print
    // "OK" with the server's own claimed count (found under review, 2026-09-01).
    final actualItemCount =
        await (db.selectOnly(db.referenceItems)
              ..addColumns([db.referenceItems.itemCode.count()])
              ..where(
                db.referenceItems.listCode.equals(r.listCode) &
                    db.referenceItems.version.equals(r.version),
              ))
            .map((row) => row.read(db.referenceItems.itemCode.count()))
            .getSingleOrNull() ??
        0;
    final claimedItemCount = row?.itemCount;
    final itemCountMatches = claimedItemCount == null || actualItemCount == claimedItemCount;

    final status = r.isFailure
        ? 'FAILED (${r.outcome.name})'
        : !itemCountMatches
        ? 'FAILED (cached $actualItemCount items, manifest claims $claimedItemCount)'
        : 'OK';
    if (r.isFailure || !itemCountMatches) allOk = false;
    stdout.writeln(
      '${r.listCode.padRight(18)} version=${r.version} '
      'itemCount=$actualItemCount/${row?.itemCount ?? '-'} '
      'contentHash=${row?.contentHash ?? '-'}: $status',
    );
  }

  await db.close();

  if (!allOk || result.results.length != 7) {
    stderr.writeln('FAILED: expected 7 successfully verified lists, got ${result.results.length}.');
    exit(1);
  }

  stdout.writeln('All ${result.results.length} lists fetched, verified and cached successfully.');
}
