import 'dart:async' show unawaited;

import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/dataentry/data_entry_models.dart';
import '../../core/dataentry/data_entry_providers.dart';
import '../../core/database/reference_database.dart';
import '../../core/database/session_database.dart' show DataEntryDraftCompanion;
import '../../core/entry/entry_models.dart' show ProfileAlreadyCompleteException;
import '../../core/forms/field_error_state.dart';
import '../../core/reference/reference_list_codes.dart';
import '../../core/reference/reference_providers.dart';
import '../entry/offline_banner.dart';
import 'address_cascade_fields.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 5 — home address (docs/journeys/customer.md). Free back-navigation to Stage 4.
class Stage5Screen extends ConsumerStatefulWidget {
  const Stage5Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage5Screen> createState() => _Stage5ScreenState();
}

class _Stage5ScreenState extends ConsumerState<Stage5Screen>
    with FieldErrorState<Stage5Screen> {
  final _stateTextController = TextEditingController();
  final _localityTextController = TextEditingController();
  final _cityController = TextEditingController();
  final _areaController = TextEditingController();
  final _streetController = TextEditingController();
  final _blockController = TextEditingController();
  final _houseNumberController = TextEditingController();

  bool _loaded = false;
  bool _submitting = false;

  ReferenceItem? _country;
  ReferenceItem? _state;
  ReferenceItem? _locality;
  String? _adminDivisionRootCode;

  @override
  void initState() {
    super.initState();
    _load();
    for (final controller in [
      _stateTextController,
      _localityTextController,
      _cityController,
      _areaController,
      _streetController,
      _blockController,
      _houseNumberController,
    ]) {
      controller.addListener(_saveDraft);
    }
  }

  @override
  void dispose() {
    _stateTextController.dispose();
    _localityTextController.dispose();
    _cityController.dispose();
    _areaController.dispose();
    _streetController.dispose();
    _blockController.dispose();
    _houseNumberController.dispose();
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
      // Defaults to Sudan when THIS field specifically is unset — not when the draft row as a
      // whole is absent. By the time a customer reaches stage 5, stage 3/4's own saves have
      // already created that row, so `draft == null` is false here on a customer's very first
      // visit to stage 5, and `draft.homeCountryCode` (genuinely still unset) was wrongly read
      // as "already answered, leave blank" instead of defaulting (found live testing back/forward
      // navigation across all four stages, S5-05).
      _country =
          findByCode(countryItems, draft?.homeCountryCode) ?? findByCode(countryItems, rootCode);
      _state = findByCode(adminDivisionItems, draft?.homeStateCode);
      _locality = findByCode(adminDivisionItems, draft?.homeLocalityCode);
      _stateTextController.text = draft?.homeStateText ?? '';
      _localityTextController.text = draft?.homeLocalityText ?? '';
      _cityController.text = draft?.homeCity ?? '';
      _areaController.text = draft?.homeArea ?? '';
      _streetController.text = draft?.homeStreet ?? '';
      _blockController.text = draft?.homeBlock ?? '';
      _houseNumberController.text = draft?.homeHouseNumber ?? '';
      _loaded = true;
    });
  }

  bool get _isSudan => _country != null && _country!.itemCode == _adminDivisionRootCode;

  /// Walk comment 2 (2026-09-10): the order the keyboard's next key walks this screen's text
  /// fields. Country/state/locality are pickers WHEN THE COUNTRY IS SUDAN, and the whole reason
  /// the next key looked dead was that Flutter's default traversal landed on one of them.
  ///
  /// **Computed on `_isSudan`, not constant.** Outside Sudan the state and locality pickers become
  /// mandatory free-text fields (`AddressCascadeFields`), and the first version of this list left
  /// them out — so a customer with a foreign address had the keyboard jump over two required
  /// fields. Found by `@agent-reviewer`.
  @override
  List<String> get textFieldOrder => [
    if (!_isSudan) ...['stateText', 'localityText'],
    'city',
    'area',
    'street',
    'block',
    'houseNumber',
  ];

  Future<void> _saveDraft() async {
    if (!_loaded) return;
    final repository = ref.read(dataEntryRepositoryProvider);
    await repository.saveDraftFields(
      DataEntryDraftCompanion(
        homeCountryCode: Value(_country?.itemCode),
        homeStateCode: Value(_isSudan ? _state?.itemCode : null),
        homeStateText: Value(_isSudan ? null : _stateTextController.text.trim()),
        homeLocalityCode: Value(_isSudan ? _locality?.itemCode : null),
        homeLocalityText: Value(_isSudan ? null : _localityTextController.text.trim()),
        homeCity: Value(_cityController.text.trim()),
        homeArea: Value(_areaController.text.trim()),
        homeStreet: Value(_streetController.text.trim()),
        homeBlock: Value(_blockController.text.trim()),
        homeHouseNumber: Value(_houseNumberController.text.trim()),
      ),
    );
  }

  /// D5.6: unchanged rules, unchanged order, unchanged messages — each one now also names the
  /// field it is about, so the message can be shown against that field. A null `field` means the
  /// rule is about a picker, which has no `errorText` of its own and keeps the summary line.
  FieldValidationError? get _validationError {
    if (_country == null) return (field: null, message: 'اختر بلد السكن.');
    if (_isSudan) {
      if (_state == null) return (field: null, message: 'اختر ولاية السكن.');
      if (_locality == null) return (field: null, message: 'اختر محلية/محافظة السكن.');
    } else {
      if (_stateTextController.text.trim().isEmpty) {
        return (field: 'stateText', message: 'أدخل ولاية السكن.');
      }
      if (_localityTextController.text.trim().isEmpty) {
        return (field: 'localityText', message: 'أدخل محلية/محافظة السكن.');
      }
    }
    if (_cityController.text.trim().isEmpty) return (field: 'city', message: 'أدخل المدينة.');
    if (_areaController.text.trim().isEmpty) return (field: 'area', message: 'أدخل المنطقة.');
    if (_streetController.text.trim().isEmpty) return (field: 'street', message: 'أدخل الشارع.');
    if (_blockController.text.trim().isEmpty) return (field: 'block', message: 'أدخل المربع.');
    if (_houseNumberController.text.trim().isEmpty) {
      return (field: 'houseNumber', message: 'أدخل رقم المنزل.');
    }
    return null;
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
      await repository.submitStage5(
        countryCode: _country!.itemCode,
        stateCode: _isSudan ? _state!.itemCode : null,
        stateText: _isSudan ? null : _stateTextController.text.trim(),
        localityCode: _isSudan ? _locality!.itemCode : null,
        localityText: _isSudan ? null : _localityTextController.text.trim(),
        city: _cityController.text.trim(),
        area: _areaController.text.trim(),
        street: _streetController.text.trim(),
        block: _blockController.text.trim(),
        houseNumber: _houseNumberController.text.trim(),
      );
      await repository.advanceToStage('stage6');
      if (!mounted) return;
      context.go('/stage-6');
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
      appBar: BrandBanner(title: const Text('عنوان السكن')),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const JourneyProgress(step: JourneyStep.homeAddress),
                if (widget.offline) const OfflineBanner(),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
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
                          onSubmitted: fieldSubmit('block'),
                          decoration: fieldDecoration(label: 'المربع', field: 'block'),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _houseNumberController,
                          focusNode: fieldNode('houseNumber'),
                          textInputAction: fieldAction('houseNumber'),
                          onSubmitted: fieldSubmit('houseNumber', onDone: _onNext),
                          decoration: fieldDecoration(label: 'رقم المنزل', field: 'houseNumber'),
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
                  onPrevious: () => context.go('/stage-4'),
                  onNext: _onNext,
                  busy: _submitting,
                ),
              ],
            ),
    );
  }
}
