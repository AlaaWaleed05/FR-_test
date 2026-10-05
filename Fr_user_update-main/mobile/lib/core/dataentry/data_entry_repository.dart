import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:path/path.dart' as p;

import '../database/session_database.dart';
import '../entry/entry_models.dart'
    show BackendUnreachableException, ProfileAlreadyCompleteException;
import '../reference/reference_list_codes.dart';
import '../reference/reference_repository.dart';
import 'data_entry_api.dart';
import 'data_entry_models.dart';

/// The five reference lists a stage 3-6 submission can touch — `prepareCatalog`'s scope and the
/// only lists this session pins (docs/components/reference-data.md client rule 4). `branch` and
/// `rejection_reason` are irrelevant here.
const _dataEntryListCodes = [
  ReferenceListCodes.country,
  ReferenceListCodes.adminDivision,
  ReferenceListCodes.occupation,
  ReferenceListCodes.incomeSource,
  ReferenceListCodes.educationLevel,
];

/// Stages 3-6's testable core (docs/journeys/customer.md), depending only on [DataEntryApi] +
/// [SessionDatabase] + [ReferenceRepository] — mirrors `EntryRepository`'s own separation.
///
/// **The offline queue** (S5-05, this project's first): each `submitStageN` method attempts the
/// real network call; on [BackendUnreachableException] it queues (`PendingStageSync`) and returns
/// `false` instead of throwing — the caller (a stage screen's "Next" handler) treats `true` and
/// `false` identically, since customer.md's "offline-capable, free navigation" means local progress
/// advances regardless of connectivity. A genuine business rejection
/// ([DataEntryRejectedException]/`ProfileAlreadyCompleteException`) is NOT queued — it propagates,
/// because it means something about the submitted data is actually wrong and silently retrying it
/// later would never succeed. [flushPending] resubmits queued stages, in stage order, the moment an
/// opportunity presents itself (a later screen's `_load()`, or the post-stage-6 placeholder) — see
/// each call site's own comment for why no connectivity-monitoring package is used instead.
///
/// No idempotency key is threaded through any of this: the backend's stage 3-6 endpoints have none
/// on the wire, and resubmitting an already-landed stage does not corrupt the column values (an
/// `UPDATE` to the same values is a no-op on the data itself) — but it is NOT free of side
/// effects: `DataEntryService`'s own Javadoc states every submission writes its own audit event,
/// so a resubmission after a successful send that failed only to record as such (e.g. the app is
/// killed between the response arriving and `PendingStageSync`'s row being deleted) leaves a
/// genuine duplicate event on the append-only chain, with `previous*` fields equal to the new
/// ones — permanent noise in a record an operator reads, not a true no-op. Accepted here as the
/// cheaper failure mode against the alternative (inventing a client-side idempotency mechanism
/// the wire contract has no field for) — see `PendingStageSync`'s doc comment.
class DataEntryRepository {
  DataEntryRepository({
    required DataEntryApi api,
    required SessionDatabase sessionDb,
    required ReferenceRepository referenceRepository,
  }) : _api = api,
       _db = sessionDb,
       _referenceRepository = referenceRepository;

  final DataEntryApi _api;
  final SessionDatabase _db;
  final ReferenceRepository _referenceRepository;

  static const _stage3 = 'stage3';
  static const _stage4 = 'stage4';
  static const _stage5 = 'stage5';
  static const _stage6 = 'stage6';
  static const _stage7 = 'stage7';
  static const _orderedStages = [_stage3, _stage4, _stage5, _stage6, _stage7];

  /// Ceiling on one salary-certificate upload. Generous — the payload can be a 10 MB PDF on a
  /// Sudanese mobile connection — but finite, because Stage 6's Next path awaits it and Dio
  /// declares no `sendTimeout` (see [uploadSalaryCertificate]).
  static const _salaryCertificateUploadTimeout = Duration(minutes: 2);

  Future<DataEntryDraftData?> loadDraft() {
    return (_db.select(_db.dataEntryDraft)..where((t) => t.id.equals(0))).getSingleOrNull();
  }

  Future<List<DataEntryIncomeSource>> loadIncomeSources() {
    return _db.select(_db.dataEntryIncomeSources).get();
  }

  /// Merges [fields] into the singleton draft row, touching only the columns [fields] explicitly
  /// sets (`Value(...)`) — every other stage's already-saved columns are left untouched. Callers
  /// must always leave `id` unset here (this method owns it) and never set `updatedAt` (this
  /// method owns that too).
  ///
  /// **`id` is always forced to `Value(0)` explicitly**, never left absent — SQLite's `INTEGER
  /// PRIMARY KEY` autoincrements when a companion leaves a column absent, even one declared
  /// `withDefault(Constant(0))` (the exact bug `ReferenceRepository`'s `ManifestState` upsert
  /// already documents finding live at S5-01; the same fix applies here).
  Future<void> saveDraftFields(DataEntryDraftCompanion fields) {
    return _db
        .into(_db.dataEntryDraft)
        .insertOnConflictUpdate(
          fields.copyWith(id: const Value(0), updatedAt: Value(DateTime.now())),
        );
  }

  /// Uploads the salary certificate at [path] and, only on success, stamps
  /// `DataEntryDraft.salaryCertificateUploadedAt` so the screen may say «تم إرفاق» truthfully
  /// (BL-105). Returns true when the bank now holds the file.
  ///
  /// **Never throws for an ordinary failure.** The certificate is optional and
  /// customer.md Stage 6 is explicit that it "gates nothing, must never block completion", so an
  /// unreachable backend, a rejection or a missing file all return false and leave the draft
  /// unstamped. The screen turns that into an honest "not attached yet" with a retry, rather than
  /// into a blocked Next — which is the same shape as the old defect if it stopped the journey.
  ///
  /// The stamp is what makes this idempotent-ish across restarts: a customer who attaches, loses
  /// connectivity and comes back still has the file on disk and an unstamped draft, so Stage 6 can
  /// retry it. Re-uploading a file the backend already has is harmless — the endpoint upserts a
  /// single artifact per profile (`upsertSalaryCertificateArtifact`).
  Future<bool> uploadSalaryCertificate(String path) async {
    // **EVERYTHING is inside the try, deliberately** — found by `@agent-reviewer` on the S8-14
    // diff. The first version guarded only the read and the API call, leaving `file.exists()` and
    // the draft write outside it. Either can throw (a `FileSystemException` on the path, a drift
    // write failure), and Stage 6 awaits this call inside `_onNext`'s try, so the escape landed in
    // the generic catch and stopped `advanceToStage`/`context.go('/stage-7')` from running. That
    // is the certificate blocking completion — precisely what customer.md Stage 6 forbids and what
    // this method's contract below promises it will never do.
    try {
      final file = File(path);
      if (!await file.exists()) return false;

      final String contentType;
      switch (p.extension(path).toLowerCase()) {
        case '.pdf':
          contentType = 'application/pdf';
        case '.png':
          contentType = 'image/png';
        default:
          // Everything this app stores that is not a PDF has been through `downscaleToJpeg`.
          contentType = 'image/jpeg';
      }

      final bytes = await file.readAsBytes();
      final profileId = await _requireProfileId();
      // **Bounded, because Dio has no `sendTimeout`** — found by `@agent-reviewer` on the second
      // S8-14 pass. `receiveTimeout` does not start until a response begins arriving, so a
      // connection that establishes and then stalls part-way through a multi-megabyte base64 body
      // has no client-side deadline at all. Stage 6 awaits this call on its Next path, so an
      // unbounded stall is the certificate blocking completion — the one thing customer.md Stage
      // 6 says it must never do. The timeout lands in the `on Object` catch below and becomes a
      // plain `false`, which is exactly the honest "not attached yet" state.
      await _api
          .uploadSalaryCertificate(
            profileId: profileId,
            contentType: contentType,
            contentBase64: base64Encode(bytes),
          )
          .timeout(_salaryCertificateUploadTimeout);

      // **Stamp only if the draft still names the file we just uploaded** — found by
      // `@agent-reviewer` on the second S8-14 pass. The draft holds ONE path and ONE stamp, and
      // the stamp is not keyed to the file it belongs to. A late-arriving success for file A was
      // therefore stamping a row that by then named file B, and Stage 6 reads that stamp back as
      // its source of truth on re-entry — so «تم إرفاق: B» came back from disk for a file the
      // bank never received. That is BL-105 restored from storage, and the screen-level guard
      // does not reach it: this is the half that touches the database.
      final current = await loadDraft();
      if (current?.salaryCertificatePath != path) return false;
      await saveDraftFields(
        DataEntryDraftCompanion(salaryCertificateUploadedAt: Value(DateTime.now())),
      );
      return true;
    } on Object {
      // Includes BackendUnreachableException, a 400 rejection, a 409, a missing file and a failed
      // local write — none of which may cost the customer their place in the journey.
      return false;
    }
  }

  /// Wholesale replace, mirroring `ReferenceRepository`'s own item-batch-replace pattern — Stage
  /// 4's whole multi-select is always re-saved together, never diffed row by row.
  Future<void> saveIncomeSources(List<IncomeSourceEntry> sources) {
    return _db.transaction(() async {
      await _db.delete(_db.dataEntryIncomeSources).go();
      await _db.batch((batch) {
        batch.insertAll(
          _db.dataEntryIncomeSources,
          sources.map(
            (s) => DataEntryIncomeSourcesCompanion.insert(
              code: s.code,
              isPrimary: Value(s.primary),
              otherText: Value(s.otherText),
            ),
          ),
        );
      });
    });
  }

  Future<String?> _profileId() async {
    final progress = await (_db.select(
      _db.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    return progress?.profileId;
  }

  Future<String> _requireProfileId() async {
    final id = await _profileId();
    if (id == null) {
      throw StateError('no in-progress data-entry session (LocalProgress.profileId is unset)');
    }
    return id;
  }

  /// Stage 2→3 boundary (reference-data.md: "the catalogue gate is the stage 2→stage 3 boundary").
  /// Syncs the catalogue (best-effort — a failure here still leaves whatever was already cached),
  /// activates and pins every list stages 3-6 need, then reports whether all five now have an
  /// active cached version. `false` means client rule 5's "block stage 3 if there is no prior
  /// cache" applies — the caller must not proceed into stage 3.
  Future<bool> prepareCatalog() async {
    try {
      await _referenceRepository.syncCatalog();
    } catch (_) {
      // Best-effort: a totally unreachable backend still leaves whatever was already cached from
      // an earlier successful sync (e.g. Stage 1a's own branch-list sync) — the loop below decides
      // whether that's enough, not this catch block.
    }
    for (final listCode in _dataEntryListCodes) {
      await _referenceRepository.activateStagedVersion(listCode);
      await _referenceRepository.pinCurrentSessionVersion(listCode, _db);
    }
    for (final listCode in _dataEntryListCodes) {
      if (!await _referenceRepository.hasActiveVersion(listCode)) return false;
    }
    return true;
  }

  Future<void> advanceToStage(String stage) {
    return (_db.update(_db.localProgress)..where((t) => t.id.equals(0))).write(
      LocalProgressCompanion(resumeStage: Value(stage)),
    );
  }

  Future<int?> pinnedVersion(String listCode) => _db.pinnedVersionFor(listCode);

  /// Per-stage FIFO mutex — found under review: `flushPending()` runs `unawaited` from every
  /// screen's `_load()`, so a background sweep resubmitting stage 3 with its pre-edit values can
  /// genuinely overlap a customer's own "Next" resubmitting stage 3 with just-edited values (back
  /// to stage 3, edit, connectivity returns, forward again — no programming error required to
  /// reach it). `DataEntryService.updateStage3` is a plain last-write-wins `UPDATE` with no
  /// version token, so two concurrent POSTs racing decide the outcome by network timing, not by
  /// which one is actually newer. Every call for the same [stage] now waits for the previous one
  /// to finish before sending its own request, so two POSTs are never in flight together — this
  /// closes the network-timing race for a screen's own explicit `submitStageN` call, whose
  /// `call()` closure always captures whatever values are on screen at the moment it actually
  /// runs, not a snapshot taken earlier.
  ///
  /// **Residual, disclosed rather than silently assumed closed (second review pass):** the flush
  /// path's own values are NOT re-read inside this lock — `_flushPending` takes one `loadDraft()`
  /// snapshot before the sweep starts (see [flushPending]), so a "Next" that saves a newer draft
  /// and then wins the lock ahead of a queued flush attempt can still be overtaken if that flush
  /// attempt was already mid-request when the edit landed and happens to win the queue behind it.
  /// Narrower than the race this fix closes (it needs the edit to land in the specific window
  /// between the flush's snapshot and its turn at the lock, not any overlapping window at all),
  /// and bounded by the fact each stage's own screen always resubmits on its own "Next" besides.
  final Map<String, Future<void>> _stageLocks = {};

  /// Runs [call]; on success clears any pending row for [stage] and returns `true`. On
  /// [BackendUnreachableException], queues [stage] and returns `false` instead of rethrowing — see
  /// this class's own doc comment for why. Any other exception (a genuine rejection) propagates
  /// unchanged.
  Future<bool> _submitOrQueue(String stage, Future<void> Function() call) async {
    final previous = _stageLocks[stage] ?? Future<void>.value();
    final ownTurn = Completer<void>();
    _stageLocks[stage] = ownTurn.future;
    try {
      await previous;
      try {
        await call();
        await _clearPending(stage);
        return true;
      } on BackendUnreachableException {
        await _markPending(stage);
        return false;
      }
    } finally {
      ownTurn.complete();
      // Only the most recent waiter clears the slot — an earlier `finally` racing this one would
      // otherwise remove a NEWER call's still-active lock entry.
      if (identical(_stageLocks[stage], ownTurn.future)) {
        _stageLocks.remove(stage);
      }
    }
  }

  Future<void> _clearPending(String stage) {
    return (_db.delete(_db.pendingStageSync)..where((t) => t.stage.equals(stage))).go();
  }

  Future<void> _markPending(String stage) {
    return _db
        .into(_db.pendingStageSync)
        .insertOnConflictUpdate(
          PendingStageSyncCompanion.insert(stage: stage, queuedAt: DateTime.now()),
        );
  }

  /// `true` if this call reached the backend (or found nothing new to send); `false` if it was
  /// queued for later. Screens await this and proceed identically either way — see this class's
  /// doc comment.
  Future<bool> submitStage3({
    required String sexDeclared,
    required String ethnicity,
    required String countryOfResidenceCode,
    required String maritalStatus,
    String? spouseName,
    bool? hasChildren,
    int? childrenCount,
    required int educationLevel,
    required String birthCountryCode,
    String? birthStateCode,
    String? birthStateText,
    required String birthCityText,
  }) async {
    final profileId = await _requireProfileId();
    final countryVersion = await _db.pinnedVersionFor(ReferenceListCodes.country);
    final adminDivisionVersion = await _db.pinnedVersionFor(ReferenceListCodes.adminDivision);
    return _submitOrQueue(
      _stage3,
      () => _api.submitStage3(
        profileId: profileId,
        sexDeclared: sexDeclared,
        ethnicity: ethnicity,
        countryOfResidenceCode: countryOfResidenceCode,
        maritalStatus: maritalStatus,
        spouseName: spouseName,
        hasChildren: hasChildren,
        childrenCount: childrenCount,
        educationLevel: educationLevel,
        birthCountryCode: birthCountryCode,
        birthStateCode: birthStateCode,
        birthStateText: birthStateText,
        birthCityText: birthCityText,
        countryListVersion: countryVersion,
        adminDivisionListVersion: adminDivisionVersion,
      ),
    );
  }

  Future<bool> submitStage4({
    required String occupationCode,
    required List<IncomeSourceEntry> incomeSources,
    required String monthlyExpensesSdg,
  }) async {
    final profileId = await _requireProfileId();
    final occupationVersion = await _db.pinnedVersionFor(ReferenceListCodes.occupation);
    final incomeSourceVersion = await _db.pinnedVersionFor(ReferenceListCodes.incomeSource);
    return _submitOrQueue(
      _stage4,
      () => _api.submitStage4(
        profileId: profileId,
        occupationCode: occupationCode,
        incomeSources: incomeSources,
        monthlyExpensesSdg: monthlyExpensesSdg,
        occupationListVersion: occupationVersion,
        incomeSourceListVersion: incomeSourceVersion,
      ),
    );
  }

  Future<bool> submitStage5({
    required String countryCode,
    String? stateCode,
    String? stateText,
    String? localityCode,
    String? localityText,
    required String city,
    required String area,
    required String street,
    required String block,
    required String houseNumber,
  }) async {
    final profileId = await _requireProfileId();
    final countryVersion = await _db.pinnedVersionFor(ReferenceListCodes.country);
    final adminDivisionVersion = await _db.pinnedVersionFor(ReferenceListCodes.adminDivision);
    return _submitOrQueue(
      _stage5,
      () => _api.submitStage5(
        profileId: profileId,
        countryCode: countryCode,
        stateCode: stateCode,
        stateText: stateText,
        localityCode: localityCode,
        localityText: localityText,
        city: city,
        area: area,
        street: street,
        block: block,
        houseNumber: houseNumber,
        countryListVersion: countryVersion,
        adminDivisionListVersion: adminDivisionVersion,
      ),
    );
  }

  Future<bool> submitStage6({
    required String employer,
    required String countryCode,
    String? stateCode,
    String? stateText,
    String? localityCode,
    String? localityText,
    required String city,
    required String area,
    required String street,
    required String block,
  }) async {
    final profileId = await _requireProfileId();
    final countryVersion = await _db.pinnedVersionFor(ReferenceListCodes.country);
    final adminDivisionVersion = await _db.pinnedVersionFor(ReferenceListCodes.adminDivision);
    // BL-122. The claim is read from the DRAFT here rather than passed in by Stage 6, for two
    // reasons. It keeps `_onNext` — whose re-entry guard, upload retry and two race fixes are all
    // load-bearing — untouched. And the draft is the stored truth about what the customer picked,
    // whereas the screen's field is UI state; the two agree, but only one survives a restart.
    //
    // It is a CLAIM ("a file is attached"), never an upload result, which is why reading it here is
    // correct even though Stage 6 sends this request BEFORE its Next-time upload retry: picking the
    // file is what makes the claim true, and no later retry outcome can change it. The backend
    // stores it monotonically, so a queued replay of this request can never erase a claim.
    //
    // Deliberately NOT `uploadSalaryCertificate`'s return value, which is false for a SUCCESSFUL
    // upload whose file has since been replaced (see its own guard above) and so is not a truthful
    // "not uploaded".
    final draft = await loadDraft();
    final certificateAttached = draft?.salaryCertificatePath != null;
    return _submitOrQueue(
      _stage6,
      () => _api.submitStage6(
        profileId: profileId,
        employer: employer,
        countryCode: countryCode,
        stateCode: stateCode,
        stateText: stateText,
        localityCode: localityCode,
        localityText: localityText,
        city: city,
        area: area,
        street: street,
        block: block,
        countryListVersion: countryVersion,
        adminDivisionListVersion: adminDivisionVersion,
        salaryCertificateAttached: certificateAttached,
      ),
    );
  }

  /// Stage 7 — the identity document type, and the last customer-entered field in the journey.
  ///
  /// Queues on an unreachable backend exactly like its four siblings, but its CALLER must not treat
  /// a queued submission as permission to move on: Stage 8 opens the Uqudo SDK and cannot run
  /// offline at all (customer.md Stage 7's "Exits" — "the customer is told a connection is needed
  /// to continue"). `Stage7Screen` is therefore the one screen in this app that reads this return
  /// value rather than discarding it.
  Future<bool> submitStage7({required String identityType}) async {
    final profileId = await _requireProfileId();
    // No reference list is involved — the two identity types are a fixed wire vocabulary
    // (`DataEntryService.IDENTITY_TYPES`), not server-supplied reference data, so there is no
    // pinned list version to send.
    return _submitOrQueue(
      _stage7,
      () => _api.submitStage7(profileId: profileId, identityType: identityType),
    );
  }

  /// Resubmits every still-`PendingStageSync` stage, rebuilt from the current `DataEntryDraft`,
  /// in stage order — stops at the first stage that queues again (still unreachable; the rest are
  /// presumably equally so) rather than hammering all four. Returns how many stages actually
  /// landed, for diagnostics/tests. A stage with no pending row is skipped without a network call.
  /// Safe to call opportunistically and often — see this class's doc comment for call sites.
  ///
  /// **Never throws** — this is called `unawaited` from every screen's `_load()` (see call
  /// sites), so an uncaught exception here would be an unhandled zone error, not a UI-visible
  /// failure. A genuine business rejection or a terminal profile IS reachable here without any
  /// bug (e.g. a stage sits queued long enough that its pinned version falls off
  /// `DataEntryService`'s floor, or an operator completes the profile manually while a stage is
  /// still queued) — found under review. A rejected stage's pending row is dropped (resubmitting
  /// unchanged data will never succeed); a terminal profile stops the whole sweep, since nothing
  /// else queued can land either.
  Future<int> flushPending() async {
    try {
      return await _flushPending();
    } catch (_) {
      return 0;
    }
  }

  Future<int> _flushPending() async {
    final pending = await _db.select(_db.pendingStageSync).get();
    final pendingStages = pending.map((r) => r.stage).toSet();
    if (pendingStages.isEmpty) return 0;

    final draft = await loadDraft();
    if (draft == null) return 0;
    var landedCount = 0;

    for (final stage in _orderedStages) {
      if (!pendingStages.contains(stage)) continue;
      bool landed;
      try {
        landed = await _resubmitStage(stage, draft);
      } on DataEntryRejectedException {
        await _clearPending(stage); // won't succeed unchanged — resurrected by the customer's own
        // next edit-and-submit on that stage, not by retrying the same rejected values forever.
        continue;
      } on ProfileAlreadyCompleteException {
        await _clearPending(stage);
        break; // the profile is terminal — nothing else queued can land either
      }
      if (landed) {
        landedCount++;
      } else {
        break; // still unreachable — the remaining pending stages presumably are too
      }
    }
    return landedCount;
  }

  Future<bool> _resubmitStage(String stage, DataEntryDraftData draft) async {
    switch (stage) {
      case _stage3:
        return submitStage3(
          sexDeclared: draft.sexDeclared!,
          ethnicity: draft.ethnicity!,
          countryOfResidenceCode: draft.countryOfResidenceCode!,
          maritalStatus: draft.maritalStatus!,
          spouseName: draft.spouseName,
          hasChildren: draft.hasChildren,
          childrenCount: draft.childrenCount,
          educationLevel: draft.educationLevel!,
          birthCountryCode: draft.birthCountryCode!,
          birthStateCode: draft.birthStateCode,
          birthStateText: draft.birthStateText,
          birthCityText: draft.birthCityText!,
        );
      case _stage4:
        final incomeSources = await loadIncomeSources();
        return submitStage4(
          occupationCode: draft.occupationCode!,
          incomeSources: incomeSources
              .map(
                (s) => IncomeSourceEntry(code: s.code, primary: s.isPrimary, otherText: s.otherText),
              )
              .toList(),
          monthlyExpensesSdg: draft.monthlyExpensesSdg!,
        );
      case _stage5:
        return submitStage5(
          countryCode: draft.homeCountryCode!,
          stateCode: draft.homeStateCode,
          stateText: draft.homeStateText,
          localityCode: draft.homeLocalityCode,
          localityText: draft.homeLocalityText,
          city: draft.homeCity!,
          area: draft.homeArea!,
          street: draft.homeStreet!,
          block: draft.homeBlock!,
          houseNumber: draft.homeHouseNumber!,
        );
      case _stage6:
        return submitStage6(
          employer: draft.workEmployer!,
          countryCode: draft.workCountryCode!,
          stateCode: draft.workStateCode,
          stateText: draft.workStateText,
          localityCode: draft.workLocalityCode,
          localityText: draft.workLocalityText,
          city: draft.workCity!,
          area: draft.workArea!,
          street: draft.workStreet!,
          block: draft.workBlock!,
        );
      case _stage7:
        return submitStage7(identityType: draft.identityType!);
      default:
        return true;
    }
  }
}
