import 'package:drift/drift.dart';
import 'package:drift/native.dart';

part 'session_database.g.dart';

/// Which reference-list version this session pinned, and when. Directly motivated by AD-002f's
/// closed decision that "the session pins one version per list at stage 3 entry"
/// (docs/components/reference-data.md client rule 4) — exercised this session by the demo
/// screen's `pinCurrentSessionVersion` call, which stands in for the real stage-3-entry hook
/// (stage 3 itself is out of scope for S5-01).
class PinnedReferenceVersions extends Table {
  TextColumn get listCode => text()();
  IntColumn get pinnedVersion => integer()();
  DateTimeColumn get pinnedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {listCode};
}

/// Every customer-entered field from stages 1a/1b, written as the customer types (S5-02;
/// AD-005 §8 names this table `local_draft`, "every customer-entered field from stages 1b and
/// 3–6"). Singleton row (`id = 0`): one device serves one active draft at a time — a second
/// account on a shared device starts only after abandonment/completion clears this row
/// (customer.md Stage 13/"one device may serve several accounts").
///
/// **Holds real customer PII** (`accountNumber`, `phoneNumber`, `emailAddress`), encrypted at
/// rest since S5-03 via `sqlite3mc` — see `session_encryption.dart` and R-025 (retired) in
/// RISKS.md.
///
/// Deliberately holds Stage 1a's `branchCode`/`accountNumber` too, even though Stage 1a itself
/// writes nothing server-side (customer.md: "no session exists yet... the session is created
/// [at 1b]") — this row exists purely so a half-typed form survives an app restart. Its presence
/// alone does NOT constitute an "in-progress session" for Stage 0's purposes; see
/// `LocalProgress`.
class LocalDraft extends Table {
  IntColumn get id => integer().withDefault(const Constant(0))();
  
  TextColumn get accountNumber => text().nullable()();
  TextColumn get phoneNumber => text().nullable()();
  BoolColumn get smsSelected => boolean().withDefault(const Constant(true))();
  // BL-086. Existing rows are deliberately unaffected by a column-default change — a customer
  // mid-draft keeps whatever they chose. Regenerate with build_runner after touching this.
  BoolColumn get whatsappSelected => boolean().withDefault(const Constant(false))();
  TextColumn get emailAddress => text().nullable()();

  /// customer.md Stage 1b: "The email channel row activates once an address is entered, **and is
  /// deselectable once active**." Meaningful only while an address is present; a customer can
  /// still deselect and later reselect without retyping the address. Added under review, S5-02.
  BoolColumn get emailSelected => boolean().withDefault(const Constant(true))();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

/// Exists only once there is something a backend actually knows about to resume — i.e. only
/// from a successful Stage 1a `account-check` (`PROCEED`) onward, never for a bare unsubmitted
/// `LocalDraft` (AD-005 §8 names this table `local_progress`: "`resume_stage`, and the
/// last-known backend answer"). Its mere presence is exactly what Stage 0
/// (`EntryRepository.launchDecision`) uses to decide whether there is an in-progress session to
/// ask the backend about at all.
///
/// `verifiedBranchCode`/`verifiedAccountNumber` are the exact pair that last passed
/// `account-check` — deliberately separate from `LocalDraft`'s own (possibly since-edited)
/// fields, so Stage 0's resume check always re-asks about the account that was actually
/// validated, not whatever is mid-edit in the draft.
class LocalProgress extends Table {
  IntColumn get id => integer().withDefault(const Constant(0))();

  TextColumn get verifiedAccountNumber => text()();

  /// `contactChannels` — 1a passed, not yet submitted 1b. `awaitingVerification` — 1b submitted,
  /// profile created, OTPs sent (Stage 2, which doesn't exist yet in this build).
  TextColumn get resumeStage => text()();
  TextColumn get profileId => text().nullable()();

  /// A small display-only cache of the last `ContactChannelsResponse.channels` (channel/state/
  /// maskedDestination, pipe- and colon-joined) — NEVER treated as authoritative per Stage 13's
  /// ownership table (system-derived data is backend-owned); it exists solely so the resumed
  /// "awaiting verification" placeholder screen has something to render without a session-status
  /// endpoint to re-fetch it from.
  TextColumn get channelsSummary => text().nullable()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

/// Every stage 3-6 customer-entered field (S5-05), written as the customer types — the other half
/// of the AD-005 §8 "local_draft" description `LocalDraft`'s own doc comment quotes ("every
/// customer-entered field from stages 1b and 3–6"). Kept as a SEPARATE table from `LocalDraft`
/// rather than literally extending it — a disclosed deviation from that wording, same reasoning
/// that already makes `PinnedReferenceVersions`/`LocalProgress` separate tables from `LocalDraft`:
/// one singleton-row draft model, split along the same stage boundary the rest of this codebase
/// already uses (`core/entry/` vs `core/dataentry/`). Singleton row (`id = 0`), same convention as
/// `LocalDraft`.
///
/// Field names mirror the backend wire contract exactly (`Stage3Request`/`Stage4Request`/
/// `Stage5Request`/`Stage6Request`) with a `home`/`work` prefix distinguishing stage 5 from stage 6's
/// otherwise-identical address shape. Every field is nullable: an incomplete draft is the normal
/// state for most of this device's lifetime, and validation lives in the screens (mirroring
/// `DataEntryService`'s own rules), not in the schema.
class DataEntryDraft extends Table {
  IntColumn get id => integer().withDefault(const Constant(0))();

  // Stage 3 — personal, social and birth data.
  TextColumn get sexDeclared => text().nullable()();
  TextColumn get ethnicity => text().nullable()();
  TextColumn get countryOfResidenceCode => text().nullable()();
  TextColumn get maritalStatus => text().nullable()();
  TextColumn get spouseName => text().nullable()();
  BoolColumn get hasChildren => boolean().nullable()();
  IntColumn get childrenCount => integer().nullable()();
  IntColumn get educationLevel => integer().nullable()();
  TextColumn get birthCountryCode => text().nullable()();
  TextColumn get birthStateCode => text().nullable()();
  TextColumn get birthStateText => text().nullable()();
  TextColumn get birthCityText => text().nullable()();

  // Stage 4 — occupation and income (income sources live in `DataEntryIncomeSources`).
  TextColumn get occupationCode => text().nullable()();
  TextColumn get monthlyExpensesSdg => text().nullable()();

  // Stage 5 — home address.
  TextColumn get homeCountryCode => text().nullable()();
  TextColumn get homeStateCode => text().nullable()();
  TextColumn get homeStateText => text().nullable()();
  TextColumn get homeLocalityCode => text().nullable()();
  TextColumn get homeLocalityText => text().nullable()();
  TextColumn get homeCity => text().nullable()();
  TextColumn get homeArea => text().nullable()();
  TextColumn get homeStreet => text().nullable()();
  TextColumn get homeBlock => text().nullable()();
  TextColumn get homeHouseNumber => text().nullable()();

  // Stage 6 — work address and employer. No locally-uploaded salary certificate persists past a
  // file PATH: there is no backend endpoint to upload it to yet (BL-022) — see
  // `docs/sessions/2026-09-02-s5-05-data-entry-screens.md`.
  TextColumn get workEmployer => text().nullable()();
  TextColumn get workCountryCode => text().nullable()();
  TextColumn get workStateCode => text().nullable()();
  TextColumn get workStateText => text().nullable()();
  TextColumn get workLocalityCode => text().nullable()();
  TextColumn get workLocalityText => text().nullable()();
  TextColumn get workCity => text().nullable()();
  TextColumn get workArea => text().nullable()();
  TextColumn get workStreet => text().nullable()();
  TextColumn get workBlock => text().nullable()();
  TextColumn get salaryCertificatePath => text().nullable()();

  /// When the file at [salaryCertificatePath] was accepted by
  /// `POST /api/v1/salary-certificate`, or null if it has not been (BL-105, S8-14).
  ///
  /// **The whole point of this column is that «تم إرفاق» must not be a guess.** A path alone only
  /// proves a file is on this handset; it says nothing about whether the bank has it, and before
  /// S8-14 the screen showed the confirmation on the strength of the path. The certificate gates
  /// nothing (customer.md Stage 6), so a failed upload must never block Next — it must simply
  /// stop the app claiming an attachment that does not exist.
  DateTimeColumn get salaryCertificateUploadedAt => dateTime().nullable()();

  // Stage 7 — identity document type (`'passport'`/`'national_id'`, `Stage7Request.identityType`).
  // It lives here rather than in an identity-scan table because customer.md Stage 13's ownership
  // table puts "identity type" squarely in the customer-entered, device-wins column alongside every
  // other field above — it is the last such field, and the last freely-revisitable stage.
  TextColumn get identityType => text().nullable()();

  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

/// Stage 4's income-source multi-select — one row per selected source, wholesale replaced on every
/// edit (mirrors how `ReferenceRepository` replaces a whole list's item batch, S5-01). `code` is
/// the wire item code (`'OTHER'` etc.), never a display label.
class DataEntryIncomeSources extends Table {
  TextColumn get code => text()();
  BoolColumn get isPrimary => boolean().withDefault(const Constant(false))();
  TextColumn get otherText => text().nullable()();

  @override
  Set<Column> get primaryKey => {code};
}

/// The offline submission queue (S5-05, the project's first): a row's mere existence means "this
/// stage's current draft has not yet been confirmed to reach the backend." Written when
/// `DataEntryRepository.submitStageN` catches `BackendUnreachableException`; deleted the moment
/// that stage submits successfully, whether from the original attempt or a later
/// `DataEntryRepository.flushPending()` sweep. No idempotency key is needed: the backend's stage
/// 3-6 endpoints are upsert-and-re-audit on every call by design (`DataEntryService`'s own Javadoc:
/// "every submission is its own audit event, first time or resubmission alike"), so resubmitting an
/// already-landed stage never corrupts the stored values — but it is not a true no-op: it still
/// writes a genuine duplicate audit event (see `DataEntryRepository`'s own doc comment for the
/// accepted trade-off this makes against inventing a client-side idempotency key).
class PendingStageSync extends Table {
  TextColumn get stage => text()();
  DateTimeColumn get queuedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {stage};
}

/// The session-scoped local database (AD-005) — cleared on completion and on abandonment, unlike
/// `ReferenceDatabase`, which persists.
///
/// Deliberately takes a [QueryExecutor] rather than opening its own file — see
/// `ReferenceDatabase`'s doc comment on why file-opening is kept out of this schema file and
/// moved to `reference_database_native.dart`'s counterpart, `session_database_native.dart`.
@DriftDatabase(
  tables: [
    PinnedReferenceVersions,
    LocalDraft,
    LocalProgress,
    DataEntryDraft,
    DataEntryIncomeSources,
    PendingStageSync,
  ],
)
class SessionDatabase extends _$SessionDatabase {
  SessionDatabase(super.executor);

  /// For tests and standalone scripts — an isolated in-memory database with no filesystem
  /// footprint.
  factory SessionDatabase.forTesting() => SessionDatabase(NativeDatabase.memory());

  @override
  int get schemaVersion => 7;

  /// v2 (S5-02): `LocalDraft`/`LocalProgress` added to a schema an S5-01 build may already have
  /// created at version 1 (`PinnedReferenceVersions` only). Without this, a device that already
  /// ran that build has a `session.sqlite` at `user_version = 1`, `onCreate` never fires again
  /// (it runs only on first-ever open), and the two new tables silently never exist — the first
  /// query against either (`launchDecision()`'s very first statement) throws `no such table`.
  /// Found by `@agent-reviewer` at S5-02: every test uses `.forTesting()` (in-memory, always a
  /// fresh `onCreate`), so no test could have caught this — see
  /// `session_database_migration_test.dart` for the regression, which opens a real v1 file
  /// first. `ReferenceDatabase` already gets this right; this brings `SessionDatabase` in line.
  ///
  /// v3 (S5-02, same review pass): `LocalDraft.emailSelected` added — a v2 database (this same
  /// session, before the fix) has `LocalDraft` but not that column.
  ///
  /// v4 (S5-05): `DataEntryDraft`/`DataEntryIncomeSources`/`PendingStageSync` added.
  ///
  /// v5 (S5-07): `DataEntryDraft.identityType` added for Stage 7. Exactly the same mutual-exclusion
  /// trap the v1→v4 / v2→v3 pair documents above, one table down: `createTable(dataEntryDraft)`
  /// below always builds the CURRENT definition, `identityType` included, so a device coming from
  /// below v4 must NOT also run the `addColumn` — hence `else if`, not a second `if`.
  ///
  /// v6 (S8-14, BL-105): `DataEntryDraft.salaryCertificateUploadedAt` added. **This is where the
  /// v5 `else if` stops being sufficient**, and getting it wrong is the trap this file has already
  /// documented twice: a device at v4 needs BOTH the v5 and the v6 `addColumn`, while a device
  /// below v4 needs NEITHER because `createTable` builds the current definition with both columns
  /// already in it. A flat `else if (from < 5) ... else if (from < 6)` would silently skip v6 on
  /// exactly the v4 devices that need it, so the two `addColumn`s are nested inside a single
  /// `else` and each tested independently. See `session_database_migration_test.dart`.
  @override
  MigrationStrategy get migration => MigrationStrategy(
    onCreate: (m) => m.createAll(),
    onUpgrade: (m, from, to) async {
      if (from < 2) {
        // createTable always builds the CURRENT table definition (emailSelected included), so a
        // fresh v1→v4 device must NOT also run the v2→v3 ALTER below — doing so duplicates the
        // column and throws (found live while testing this exact migration, S5-02).
        await m.createTable(localDraft);
        await m.createTable(localProgress);
      } else if (from < 3) {
        await m.addColumn(localDraft, localDraft.emailSelected);
      }
      if (from < 4) {
        // createTable builds the CURRENT definition — identityType and
        // salaryCertificateUploadedAt included — so neither addColumn below may also run.
        await m.createTable(dataEntryDraft);
        await m.createTable(dataEntryIncomeSources);
        await m.createTable(pendingStageSync);
      } else {
        // Independent, not chained: a v4 device needs both of these, a v5 device only the second.
        if (from < 5) {
          await m.addColumn(dataEntryDraft, dataEntryDraft.identityType);
        }
        if (from < 6) {
          await m.addColumn(dataEntryDraft, dataEntryDraft.salaryCertificateUploadedAt);
        }
      }
    },
  );

  Future<void> pinVersion(String listCode, int version) {
    return into(pinnedReferenceVersions).insertOnConflictUpdate(
      PinnedReferenceVersionsCompanion.insert(
        listCode: listCode,
        pinnedVersion: version,
        pinnedAt: DateTime.now(),
      ),
    );
  }

  Future<int?> pinnedVersionFor(String listCode) async {
    final row = await (select(
      pinnedReferenceVersions,
    )..where((t) => t.listCode.equals(listCode))).getSingleOrNull();
    return row?.pinnedVersion;
  }

  /// Clears every session-scoped table. Called on journey completion and on explicit
  /// abandonment (docs/journeys/customer.md Stage 13).
  Future<void> clear() async {
    await delete(pinnedReferenceVersions).go();
    await delete(localDraft).go();
    await delete(localProgress).go();
    await delete(dataEntryDraft).go();
    await delete(dataEntryIncomeSources).go();
    await delete(pendingStageSync).go();
  }
}
