import 'dart:async' show unawaited;

import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/dataentry/data_entry_models.dart';
import '../../core/dataentry/data_entry_providers.dart';
import '../../core/database/reference_database.dart';
import '../../core/database/session_database.dart' show DataEntryDraftCompanion;
import '../../core/entry/entry_models.dart' show DataEntryStage, ProfileAlreadyCompleteException;
import '../../core/forms/field_error_state.dart';
import '../../core/reference/reference_list_codes.dart';
import '../../core/reference/reference_providers.dart';
import '../entry/offline_banner.dart';
import 'address_cascade_fields.dart';
import 'salary_certificate_field.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 6 — work address and employer (docs/journeys/customer.md). Free back-navigation
/// to Stage 5. Last of the four offline-capable data-entry stages — Stage 7 onward is out of
/// scope this session, so "Next" here lands on the placeholder `SessionPendingScreen`.
class Stage6Screen extends ConsumerStatefulWidget {
  const Stage6Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage6Screen> createState() => _Stage6ScreenState();
}

class _Stage6ScreenState extends ConsumerState<Stage6Screen>
    with FieldErrorState<Stage6Screen> {
  final _employerController = TextEditingController();
  final _stateTextController = TextEditingController();
  final _localityTextController = TextEditingController();
  final _cityController = TextEditingController();
  final _areaController = TextEditingController();
  final _streetController = TextEditingController();
  final _blockController = TextEditingController();

  bool _loaded = false;
  bool _submitting = false;

  ReferenceItem? _country;
  ReferenceItem? _state;
  ReferenceItem? _locality;
  String? _adminDivisionRootCode;
  String? _salaryCertificatePath;

  /// Whether the backend has accepted the file at [_salaryCertificatePath] (BL-105). Loaded from
  /// the draft so it survives leaving and re-entering Stage 6, and cleared the instant a new file
  /// is picked — otherwise a replacement would inherit the previous file's confirmation.
  bool _certificateUploaded = false;
  bool _certificateUploading = false;

  @override
  void initState() {
    super.initState();
    _load();
    for (final controller in [
      _employerController,
      _stateTextController,
      _localityTextController,
      _cityController,
      _areaController,
      _streetController,
      _blockController,
    ]) {
      controller.addListener(_saveDraft);
    }
  }

  @override
  void dispose() {
    _employerController.dispose();
    _stateTextController.dispose();
    _localityTextController.dispose();
    _cityController.dispose();
    _areaController.dispose();
    _streetController.dispose();
    _blockController.dispose();
    disposeFieldNodes();
    super.dispose();
  }

  Future<void> _load() async {
    final repository = ref.read(dataEntryRepositoryProvider);
    final referenceRepository = ref.read(referenceRepositoryProvider);
    unawaited(repository.flushPending());

    final draft = await repository.loadDraft();
    final rootCode = await referenceRepository.rootItemCode(ReferenceListCodes.adminDivision);
    final countryItems = await ref.read(countryItemsProvider.future);
    final adminDivisionItems = await ref.read(adminDivisionItemsProvider.future);
    if (!mounted) return;

    ReferenceItem? findByCode(List<ReferenceItem> items, String? code) {
      if (code == null) return null;
      for (final item in items) {
        if (item.itemCode == code) return item;
      }
      return null;
    }

    setState(() {
      _adminDivisionRootCode = rootCode;
      _employerController.text = draft?.workEmployer ?? '';
      // Same fix as Stage5Screen — default to Sudan when THIS field is unset, not when the whole
      // draft row is absent (it never is, by the time stage 6 is reached).
      _country =
          findByCode(countryItems, draft?.workCountryCode) ?? findByCode(countryItems, rootCode);
      _state = findByCode(adminDivisionItems, draft?.workStateCode);
      _locality = findByCode(adminDivisionItems, draft?.workLocalityCode);
      _stateTextController.text = draft?.workStateText ?? '';
      _localityTextController.text = draft?.workLocalityText ?? '';
      _cityController.text = draft?.workCity ?? '';
      _areaController.text = draft?.workArea ?? '';
      _streetController.text = draft?.workStreet ?? '';
      _blockController.text = draft?.workBlock ?? '';
      _salaryCertificatePath = draft?.salaryCertificatePath;
      _certificateUploaded = draft?.salaryCertificateUploadedAt != null;
      _loaded = true;
    });
  }

  bool get _isSudan => _country != null && _country!.itemCode == _adminDivisionRootCode;

  /// Walk comment 2 (2026-09-10). Employer first, then the work-address block — the pickers
  /// between them are skipped, which is the whole point (see `FieldErrorState.textFieldOrder`).
  ///
  /// **Computed on `_isSudan`, not constant, and this screen is where it mattered most.** Outside
  /// Sudan the state and locality pickers become mandatory free-text fields sitting BETWEEN
  /// employer and city, so the first version of this list jumped straight over both. They also had
  /// no focus node, so a later Next could neither focus them nor render its own error — the
  /// customer pressed Next and nothing happened, which is walk comment 2's symptom exactly.
  /// Found by `@agent-reviewer`.
  @override
  List<String> get textFieldOrder => [
    'employer',
    if (!_isSudan) ...['stateText', 'localityText'],
    'city',
    'area',
    'street',
    'block',
  ];

  Future<void> _saveDraft() async {
    if (!_loaded) return;
    final repository = ref.read(dataEntryRepositoryProvider);
    await repository.saveDraftFields(
      DataEntryDraftCompanion(
        workEmployer: Value(_employerController.text.trim()),
        workCountryCode: Value(_country?.itemCode),
        workStateCode: Value(_isSudan ? _state?.itemCode : null),
        workStateText: Value(_isSudan ? null : _stateTextController.text.trim()),
        workLocalityCode: Value(_isSudan ? _locality?.itemCode : null),
        workLocalityText: Value(_isSudan ? null : _localityTextController.text.trim()),
        workCity: Value(_cityController.text.trim()),
        workArea: Value(_areaController.text.trim()),
        workStreet: Value(_streetController.text.trim()),
        workBlock: Value(_blockController.text.trim()),
        salaryCertificatePath: Value(_salaryCertificatePath),
      ),
    );
  }

  /// D5.6: unchanged rules, unchanged order, unchanged messages — each one now also names the
  /// field it is about, so the message can be shown against that field. A null `field` means the
  /// rule is about a picker or a whole-form condition, which has no `errorText` of its own and
  /// keeps the summary line.
  FieldValidationError? get _validationError {
    if (_employerController.text.trim().isEmpty) {
      return (field: 'employer', message: 'أدخل جهة العمل.');
    }
    if (_country == null) return (field: null, message: 'اختر بلد العمل.');
    if (_isSudan) {
      if (_state == null) return (field: null, message: 'اختر ولاية العمل.');
      if (_locality == null) return (field: null, message: 'اختر محلية/محافظة العمل.');
    } else {
      if (_stateTextController.text.trim().isEmpty) {
        return (field: 'stateText', message: 'أدخل ولاية العمل.');
      }
      if (_localityTextController.text.trim().isEmpty) {
        return (field: 'localityText', message: 'أدخل محلية/محافظة العمل.');
      }
    }
    if (_cityController.text.trim().isEmpty) return (field: 'city', message: 'أدخل المدينة.');
    if (_areaController.text.trim().isEmpty) return (field: 'area', message: 'أدخل المنطقة.');
    if (_streetController.text.trim().isEmpty) return (field: 'street', message: 'أدخل الشارع.');
    if (_blockController.text.trim().isEmpty) return (field: 'block', message: 'أدخل المربع.');
    return null;
  }

  /// Saves the newly-picked path (clearing any previous acceptance stamp) and then uploads it.
  Future<void> _onCertificatePicked() async {
    // **The new path and the cleared stamp go down in ONE write** — found by `@agent-reviewer` on
    // the second S8-14 pass. Doing it as two (`_saveDraft()` storing the path, then a separate
    // companion clearing the stamp) leaves the row durably claiming the NEW file was accepted for
    // the window between the two awaits, and this method is invoked unawaited, so a throw on the
    // second write simply left the stale stamp in place. No concurrency needed to hit it.
    await ref.read(dataEntryRepositoryProvider).saveDraftFields(
      DataEntryDraftCompanion(
        salaryCertificatePath: Value(_salaryCertificatePath),
        salaryCertificateUploadedAt: const Value(null),
      ),
    );
    // The rest of Stage 6's fields; it re-writes the same path and never touches the stamp.
    await _saveDraft();
    if (_salaryCertificatePath == null) return;
    await _uploadCertificate();
  }

  /// BL-105. Uploads the picked certificate and records the outcome honestly.
  ///
  /// Deliberately fire-and-forget from the customer's point of view: it never blocks Next and
  /// never shows a blocking error, because customer.md Stage 6 says this attachment "gates
  /// nothing, must never block completion". The only thing that changes on failure is what the
  /// screen CLAIMS — which is the entire defect being fixed.
  Future<void> _uploadCertificate() async {
    final path = _salaryCertificatePath;
    if (path == null) return;
    setState(() => _certificateUploading = true);
    try {
      final ok = await ref.read(dataEntryRepositoryProvider).uploadSalaryCertificate(path);
      if (!mounted) return;
      // **Discard a result that no longer describes what the screen is showing** — found by
      // `@agent-reviewer` on the S8-14 diff. Pick A, upload A in flight, pick B, then A succeeds:
      // applying that result unconditionally marks the field «تم إرفاق» while the file it names
      // is B, which the bank does not have. That is the BL-105 defect rebuilt out of a race, so
      // the captured path is compared against the current one before anything is claimed.
      if (path != _salaryCertificatePath) return;
      setState(() => _certificateUploaded = ok);
    } finally {
      // **A `finally`, not a trailing setState** — same review. Without it, an escape from the
      // repository leaves `_certificateUploading` true for ever: the field renders «جارٍ إرفاق»
      // permanently and `onRetryUpload` is null while uploading, so the customer has no route
      // back to a retry at all.
      if (mounted) setState(() => _certificateUploading = false);
    }
  }

  Future<void> _onNext() async {
    // **Re-entry guard (walk comment 2, 2026-09-10).** Until the keyboard's `done` key could
    // submit, the ONLY caller was `StageActionBar`, which disables itself while `busy` — so the
    // guard lived on the widget. `done` is a second entry point that the widget cannot disable
    // (the text fields stay enabled during a submit), so a customer pressing it twice fired two
    // submissions, two `advanceToStage` writes and two navigations. Found by `@agent-reviewer`.
    if (_submitting) return;
    final error = _validationError;
    if (error != null) {
      showFieldError(field: error.field, message: error.message);
      return;
    }
    setState(() {
      _submitting = true;
      clearFieldError();
    });
    await _saveDraft();
    final repository = ref.read(dataEntryRepositoryProvider);
    try {
      await repository.submitStage6(
        employer: _employerController.text.trim(),
        countryCode: _country!.itemCode,
        stateCode: _isSudan ? _state!.itemCode : null,
        stateText: _isSudan ? null : _stateTextController.text.trim(),
        localityCode: _isSudan ? _locality!.itemCode : null,
        localityText: _isSudan ? null : _localityTextController.text.trim(),
        city: _cityController.text.trim(),
        area: _areaController.text.trim(),
        street: _streetController.text.trim(),
        block: _blockController.text.trim(),
      );
      // BL-105: one more best-effort attempt for a certificate that was picked while the
      // connection was down. Awaited so a success is reflected before leaving, but its result
      // gates nothing — a failure still advances, and Stage 6 will show the honest state if the
      // customer comes back.
      // `!_certificateUploading` matters: without it, tapping Next while the attach-time upload
      // is still in flight starts a SECOND concurrent POST of the same file and the backend
      // stores the certificate twice (`@agent-reviewer`, second S8-14 pass).
      if (_salaryCertificatePath != null &&
          !_certificateUploaded &&
          !_certificateUploading) {
        await repository.uploadSalaryCertificate(_salaryCertificatePath!);
      }
      await repository.advanceToStage(DataEntryStage.stage7.name);
      if (!mounted) return;
      context.go('/stage-7');
    } on ProfileAlreadyCompleteException {
      if (!mounted) return;
      context.go('/ended');
    } on DataEntryRejectedException {
      if (!mounted) return;
      showFieldError(message: 'تعذر حفظ البيانات. يرجى مراجعة الحقول والمحاولة مرة أخرى.');
    } catch (_) {
      if (!mounted) return;
      showFieldError(message: 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(title: const Text('عنوان العمل')),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const JourneyProgress(step: JourneyStep.workAddress),
                if (widget.offline) const OfflineBanner(),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        TextField(
                          controller: _employerController,
                          focusNode: fieldNode('employer'),
                          textInputAction: fieldAction('employer'),
                          onSubmitted: fieldSubmit('employer'),
                          decoration: fieldDecoration(label: 'جهة العمل', field: 'employer'),
                        ),
                        const SizedBox(height: 8),
                        AddressCascadeFields(
                          country: _country,
                          stateItem: _state,
                          localityItem: _locality,
                          stateTextController: _stateTextController,
                          localityTextController: _localityTextController,
                          // Walk comment 2 (2026-09-10): the two non-Sudan free-text fields join
                          // this screen's keyboard chain. Node and handler come from the screen,
                          // because the ORDER belongs to `textFieldOrder`, not to the widget.
                          stateTextFocus: fieldNode('stateText'),
                          localityTextFocus: fieldNode('localityText'),
                          stateTextAction: fieldAction('stateText'),
                          localityTextAction: fieldAction('localityText'),
                          onStateTextSubmitted: fieldSubmit('stateText'),
                          onLocalityTextSubmitted: fieldSubmit('localityText'),
                          adminDivisionRootCode: _adminDivisionRootCode,
                          onCountryChanged: (item) {
                            setState(() {
                              _country = item;
                              _state = null;
                              _locality = null;
                            });
                            _saveDraft();
                          },
                          onStateChanged: (item) {
                            setState(() {
                              _state = item;
                              _locality = null; // cascade reset
                            });
                            _saveDraft();
                          },
                          onLocalityChanged: (item) {
                            setState(() => _locality = item);
                            _saveDraft();
                          },
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _cityController,
                          focusNode: fieldNode('city'),
                          textInputAction: fieldAction('city'),
                          onSubmitted: fieldSubmit('city'),
                          decoration: fieldDecoration(label: 'المدينة', field: 'city'),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _areaController,
                          focusNode: fieldNode('area'),
                          textInputAction: fieldAction('area'),
                          onSubmitted: fieldSubmit('area'),
                          decoration: fieldDecoration(label: 'المنطقة', field: 'area'),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _streetController,
                          focusNode: fieldNode('street'),
                          textInputAction: fieldAction('street'),
                          onSubmitted: fieldSubmit('street'),
                          decoration: fieldDecoration(label: 'الشارع', field: 'street'),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _blockController,
                          focusNode: fieldNode('block'),
                          textInputAction: fieldAction('block'),
                          onSubmitted: fieldSubmit('block', onDone: _onNext),
                          decoration: fieldDecoration(label: 'المربع', field: 'block'),
                        ),
                        const SizedBox(height: 16),
                        SalaryCertificateField(
                          path: _salaryCertificatePath,
                          uploaded: _certificateUploaded,
                          uploading: _certificateUploading,
                          onRetryUpload:
                              _salaryCertificatePath != null &&
                                  !_certificateUploaded &&
                                  !_certificateUploading
                              ? _uploadCertificate
                              : null,
                          onChanged: (path) {
                            setState(() {
                              _salaryCertificatePath = path;
                              // A new file has NOT been accepted yet, whatever the old one's
                              // state was. Without this a replacement inherits «تم إرفاق».
                              _certificateUploaded = false;
                            });
                            unawaited(_onCertificatePicked());
                          },
                        ),
                        if (hasFormLevelError) ...[
                          const SizedBox(height: 12),
                          Text(
                            fieldErrorMessage!,
                            style: TextStyle(color: Theme.of(context).colorScheme.error),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                StageActionBar.previousNext(
                  onPrevious: () => context.go('/stage-5'),
                  onNext: _onNext,
                  busy: _submitting,
                ),
              ],
            ),
    );
  }
}
