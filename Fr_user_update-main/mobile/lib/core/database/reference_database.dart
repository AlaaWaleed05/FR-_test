import 'package:drift/drift.dart';
import 'package:drift/native.dart';

part 'reference_database.g.dart';

/// One row per `(listCode, version)` this device has ever verified. [isActiveVersion]
/// distinguishes the version the app currently reads from a newly verified version staged
/// alongside it — AD-002f's staging requirement: "a newly verified version can sit ready
/// without becoming active mid-journey" (docs/components/reference-data.md).
///
/// Deliberately NOT named `isActive` — that name is reserved for `ReferenceItems`' own
/// item-enabled wire field below, and conflating the two was the single biggest naming trap in
/// this schema.
class ReferenceLists extends Table {
  TextColumn get listCode => text()();
  IntColumn get version => integer()();
  TextColumn get contentHash => text()();
  IntColumn get itemCount => integer()();
  BoolColumn get isHierarchical => boolean()();
  TextColumn get rootItemCode => text().nullable()();
  IntColumn get rootCountryVersion => integer().nullable()();
  TextColumn get nameAr => text()();
  TextColumn get nameEn => text()();
  DateTimeColumn get publishedAt => dateTime()();
  DateTimeColumn get fetchedAt => dateTime()();
  BoolColumn get isActiveVersion => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {listCode, version};
}

/// Every cached item row for a specific `(listCode, version)`. `searchAr`/`searchEn` and
/// `sortOrdinal` arrive server-computed and are stored verbatim — the device never folds labels
/// or re-sorts Arabic itself (docs/components/persistence.md's Arabic rule; client rule 6 in
/// docs/components/reference-data.md).
///
/// `labelEn`/`searchEn` ARE nullable, observed live (2026-09-01) against a real published
/// `branch` document: several branches have no English name seeded, so `label_en` and the
/// `search_en` generated from it are both null for those rows. `labelAr`/`searchAr` are NOT
/// NULL on the wire (AD-005) and stay non-nullable here.
class ReferenceItems extends Table {
  TextColumn get listCode => text()();
  IntColumn get version => integer()();
  TextColumn get itemCode => text()();
  TextColumn get parentCode => text().nullable()();
  TextColumn get labelAr => text()();
  TextColumn get labelEn => text().nullable()();
  TextColumn get searchAr => text()();
  TextColumn get searchEn => text().nullable()();
  IntColumn get sortOrdinal => integer()();
  BoolColumn get isActive => boolean()();
  TextColumn get extraJson => text().nullable()();

  @override
  Set<Column> get primaryKey => {listCode, version, itemCode};

  // Composite FK — drift's column-level `references()` cannot express a two-column reference,
  // so it is declared as a raw table constraint (matches this project's existing preference for
  // a real constraint over an unenforced convention — see the backend's V0049 composite FK).
  @override
  List<String> get customConstraints => [
    'FOREIGN KEY (list_code, version) REFERENCES reference_lists (list_code, version) '
        'ON DELETE CASCADE',
  ];
}

/// Bounded-backoff / poison state (docs/components/reference-data.md client rule 5): discard a
/// verification failure, never adopt unverified data, poison a `(listCode, version)` after its
/// second consecutive VERIFICATION failure. Lives in the PERSISTENT database, not the session
/// one — poisoning describes distrust of a specific server-published artifact, not per-session
/// state, and a row is cleared the next time that same `(listCode, version)` verifies
/// successfully.
///
/// Two separate counters, not one: `totalFailureCount` paces backoff for EVERY failure kind
/// (a transient network error included — a flaky link still needs pacing, not just a wall to
/// bang against immediately); `verificationFailureCount` counts only hash-mismatch/malformed-
/// document failures and is the one poisoning actually keys on, matching rule 5's own wording
/// ("poison the version after two failures" reads in context as two verification failures, not
/// two failures of any kind — corrected under review, 2026-09-01, after an earlier draft
/// poisoned a version purely from two dropped connections).
class ReferenceListFailures extends Table {
  TextColumn get listCode => text()();
  IntColumn get version => integer()();
  IntColumn get totalFailureCount => integer().withDefault(const Constant(0))();
  IntColumn get verificationFailureCount => integer().withDefault(const Constant(0))();
  DateTimeColumn get lastFailureAt => dateTime().nullable()();
  BoolColumn get isPoisoned => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {listCode, version};
}

/// Singleton row caching the manifest's last-seen `catalogHash`/`generatedAt`, used to send
/// `If-None-Match` on the next manifest fetch (docs/components/reference-data.md: "conditional
/// requests where the server supports them").
class ManifestState extends Table {
  IntColumn get id => integer().withDefault(const Constant(0))();
  TextColumn get catalogHash => text()();
  DateTimeColumn get generatedAt => dateTime()();
  DateTimeColumn get fetchedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

/// The persistent local database (AD-005) — never cleared by the app itself. Holds every
/// reference list this device has ever verified, plus the failure/poison bookkeeping for lists
/// that failed verification. See `SessionDatabase` for the database that IS cleared, on
/// completion and abandonment.
///
/// Deliberately takes a [QueryExecutor] rather than opening its own file — resolving a real
/// on-device file path needs `path_provider`, a Flutter plugin that transitively pulls in the
/// Flutter engine (`dart:ui`). Keeping that out of this file means this class, and everything
/// that only needs its schema/queries (tests, `bin/live_reference_fetch_proof.dart`), stays
/// runnable under plain `dart run` with no Flutter engine involved. See
/// `reference_database_native.dart` for the production file-opening factory.
@DriftDatabase(tables: [ReferenceLists, ReferenceItems, ReferenceListFailures, ManifestState])
class ReferenceDatabase extends _$ReferenceDatabase {
  ReferenceDatabase(super.executor);

  /// For tests and standalone scripts — an isolated in-memory database with no filesystem
  /// footprint.
  factory ReferenceDatabase.forTesting() => ReferenceDatabase(NativeDatabase.memory());

  @override
  int get schemaVersion => 1;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onCreate: (m) async {
      await m.createAll();
      // Defense-in-depth alongside the application-level promotion transaction
      // (ReferenceRepository.activateStagedVersion): at most one active version per list.
      await customStatement(
        'CREATE UNIQUE INDEX idx_reference_lists_one_active '
        'ON reference_lists (list_code) WHERE is_active_version = 1',
      );
      await customStatement(
        'CREATE INDEX idx_reference_items_sort '
        'ON reference_items (list_code, version, sort_ordinal)',
      );
    },
    beforeOpen: (details) async {
      // SQLite defaults foreign-key enforcement to OFF per connection; the composite FK on
      // `ReferenceItems` (`customConstraints` above) is otherwise emitted into `CREATE TABLE`
      // but inert — found under review, 2026-09-01.
      await customStatement('PRAGMA foreign_keys = ON');
    },
  );
}
