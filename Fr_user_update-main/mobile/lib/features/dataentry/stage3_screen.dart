import 'dart:async' show unawaited;

import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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
import '../../core/text/arabic_digit_input_formatter.dart';
import '../../core/theme/app_theme.dart';
import '../entry/offline_banner.dart';
import 'reference_item_picker.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/widgets/journey_progress.dart';

const _maritalStatuses = ['single', 'married', 'divorced', 'widowed'];

/// Journey Stage 3 — personal, social and birth data (docs/journeys/customer.md). The first of
/// four offline-capable data-entry stages. Reached either fresh from Stage 2's "Next" (once
/// `DataEntryRepository.prepareCatalog()` has succeeded) or resumed by `LaunchScreen`.
///
/// Sex is asked first because it genders everything after it: the marital-status labels, and the
/// single `spouseName` field's own label (field-provenance's separate "husband's name"/"wife's
/// name" paper-form fields collapse to one backend string — the label is this screen's only lever
/// to ask the right question).
class Stage3Screen extends ConsumerStatefulWidget {
  const Stage3Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage3Screen> createState() => _Stage3ScreenState();
}

class _Stage3ScreenState extends ConsumerState<Stage3Screen>
    with FieldErrorState<Stage3Screen> {
  final _ethnicityController = TextEditingController();
  final _spouseNameController = TextEditingController();
  final _childrenCountController = TextEditingController();
  final _birthStateTextController = TextEditingController();
  final _birthCityController = TextEditingController();

  bool _loaded = false;
  bool _submitting = false;

  String? _sex;
  ReferenceItem? _countryOfResidence;
  String? _maritalStatus;
  bool? _hasChildren;
  int? _educationLevel;
  ReferenceItem? _birthCountry;
  ReferenceItem? _birthState;

  /// admin_division's declared cascade root (the Sudan code) — never hardcoded, per
  /// reference-data.md client rule 7.
  String? _adminDivisionRootCode;

  @override
  void initState() {
    super.initState();
    _load();
    for (final controller in [
      _ethnicityController,
      _spouseNameController,
      _childrenCountController,
      _birthStateTextController,
      _birthCityController,
    ]) {
      // `_saveDraft` only — NO setState. Until 2026-09-07 this rebuilt the entire screen on
      // every keystroke of all five controllers, solely to repaint `_childrenCountController`'s
      // live Arabic numeral-agreement label. D8.4 deleted that label, and it was the only
      // build-time read of any of these controllers, so the rebuild now buys nothing and costs a
      // full layout per character on a 2 GB device. Validation reads the controllers directly at
      // submit time and reports through `showFieldError`, which has its own setState, so errors
      // are unaffected.
      controller.addListener(_saveDraft);
    }
  }

  @override
  void dispose() {
    _ethnicityController.dispose();
    _spouseNameController.dispose();
    _childrenCountController.dispose();
    _birthStateTextController.dispose();
    _birthCityController.dispose();
    disposeFieldNodes();
    super.dispose();
  }

  Future<void> _load() async {
    final repository = ref.read(dataEntryRepositoryProvider);
    final referenceRepository = ref.read(referenceRepositoryProvider);
    // Opportunistic flush — a customer who regained connectivity while browsing back to a stage
    // this local progress has already passed should not need to reach the end of the journey
    // again before it lands (see `DataEntryRepository`'s own doc comment on call sites).
    unawaited(repository.flushPending());

    final draft = await repository.loadDraft();
    final rootCode = await referenceRepository.rootItemCode(ReferenceListCodes.adminDivision);
    final countryItems = await ref.read(countryItemsProvider.future);
    ReferenceItem? findCountry(String? code) =>
        code == null ? null : _findByCode(countryItems, code);
    final adminDivisionItems = await ref.read(adminDivisionItemsProvider.future);
    ReferenceItem? findState(String? code) =>
        code == null ? null : _findByCode(adminDivisionItems, code);
    // Checked immediately before setState, after every await — found under review, S5-05: the
    // previous placement (right after the first two awaits) still left two more awaits
    // (`countryItemsProvider.future`/`adminDivisionItemsProvider.future`) unguarded, so a screen
    // disposed during either (fast back-navigation, a route replacement) could still call
    // `setState` on a defunct State. Matches how Stage4/5/6Screen already place this check.
    if (!mounted) return;

    setState(() {
      _adminDivisionRootCode = rootCode;
      _sex = draft?.sexDeclared;
      _ethnicityController.text = draft?.ethnicity ?? '';
      // Walk comment 3 of the 2026-09-10 walk («Walk comment 2a» further down this file is from
      // an earlier one). This was the ONE country picker in the app with no default, so «المواطنة»
      // opened empty while birth country (below), home country (Stage 5) and work country
      // (Stage 6) all opened on Sudan. Product-owner ruling from the walk test — Sudan is the
      // market, so it is the default everywhere a country is asked.
      //
      // customer.md's field 11 did NOT state a default (only field 22 did); that document has
      // been updated in the same commit, so the spec and this line agree. Same field-level
      // (not row-level) unset check as field 22 below, and for the same reason.
      _countryOfResidence =
          findCountry(draft?.countryOfResidenceCode) ?? findCountry(rootCode);
      _maritalStatus = draft?.maritalStatus;
      _spouseNameController.text = draft?.spouseName ?? '';
      _hasChildren = draft?.hasChildren;
      _childrenCountController.text = draft?.childrenCount?.toString() ?? '';
      _educationLevel = draft?.educationLevel;
      // Field 22: "defaulting to Sudan" — only when THIS field is unset (mirrors the identical
      // fix in Stage5Screen/Stage6Screen: checking the whole draft row's existence, rather than
      // this one field's, would wrongly skip the default the moment any OTHER stage 3 field had
      // already been saved).
      _birthCountry =
          findCountry(draft?.birthCountryCode) ?? findCountry(rootCode);
      _birthState = findState(draft?.birthStateCode);
      _birthStateTextController.text = draft?.birthStateText ?? '';
      _birthCityController.text = draft?.birthCityText ?? '';
      _loaded = true;
    });
  }

  ReferenceItem? _findByCode(List<ReferenceItem> items, String code) {
    for (final item in items) {
      if (item.itemCode == code) return item;
    }
    return null;
  }

  /// Walk comment 2 (2026-09-10). **Computed, not a constant**, because most of this screen's
  /// text fields are conditional: the spouse name exists only when married, the children count
  /// only when there are children, and the free-text birth state only outside Sudan. A fixed list
  /// would point the next key at a field that is not on screen.
  ///
  /// Everything between these entries — the sex and marital-status dropdowns, the citizenship and
  /// birth-country pickers, the education list — is deliberately absent. Those are exactly what
  /// Flutter's default traversal was landing on, which is why the key appeared to do nothing.
  @override
  List<String> get textFieldOrder => [
    'ethnicity',
    if (_maritalStatus == 'married') 'spouseName',
    if (_hasChildren == true) 'childrenCount',
    if (!_birthCountryIsSudan) 'birthStateText',
    'birthCity',
  ];

  bool get _birthCountryIsSudan =>
      _birthCountry != null && _birthCountry!.itemCode == _adminDivisionRootCode;

  Future<void> _saveDraft() async {
    if (!_loaded) return;
    final repository = ref.read(dataEntryRepositoryProvider);
    await repository.saveDraftFields(
      DataEntryDraftCompanion(
        sexDeclared: Value(_sex),
        ethnicity: Value(_ethnicityController.text.trim()),
        countryOfResidenceCode: Value(_countryOfResidence?.itemCode),
        maritalStatus: Value(_maritalStatus),
        spouseName: Value(_maritalStatus == 'married' ? _spouseNameController.text.trim() : null),
        hasChildren: Value(_maritalStatus == 'single' ? null : _hasChildren),
        childrenCount: Value(_hasChildren == true ? int.tryParse(_childrenCountController.text) : null),
        educationLevel: Value(_educationLevel),
        birthCountryCode: Value(_birthCountry?.itemCode),
        birthStateCode: Value(_birthCountryIsSudan ? _birthState?.itemCode : null),
        birthStateText: Value(
          _birthCountryIsSudan ? null : _birthStateTextController.text.trim(),
        ),
        birthCityText: Value(_birthCityController.text.trim()),
      ),
    );
  }

  /// D5.6: unchanged rules, unchanged order, unchanged messages — each one now also names the
  /// field it is about, so the message can be shown against that field. A null `field` means the
  /// rule is about a picker or a whole-form condition, which has no `errorText` of its own and
  /// keeps the summary line.
  FieldValidationError? get _validationError {
    if (_sex == null) return (field: null, message: 'اختر النوع.');
    if (_ethnicityController.text.trim().isEmpty) {
      return (field: 'ethnicity', message: 'أدخل الجنس (العرق).');
    }
    if (_countryOfResidence == null) return (field: null, message: 'اختر المواطنة.');
    if (_maritalStatus == null) return (field: null, message: 'اختر الحالة الاجتماعية.');
    if (_maritalStatus == 'married' && _spouseNameController.text.trim().isEmpty) {
      return (
        field: 'spouseName',
        message: _sex == 'm' ? 'أدخل اسم الزوجة.' : 'أدخل اسم الزوج.',
      );
    }
    if (_maritalStatus != 'single') {
      if (_hasChildren == null) {
        return (field: null, message: 'حدد ما إذا كان لديك أطفال.');
      }
      if (_hasChildren == true) {
        final n = int.tryParse(_childrenCountController.text);
        if (n == null || n < 1 || n > 30) {
          return (field: 'childrenCount', message: 'أدخل عدد الأطفال (1 إلى 30).');
        }
      }
    }
    if (_educationLevel == null) return (field: null, message: 'اختر المستوى التعليمي.');
    if (_birthCountry == null) return (field: null, message: 'اختر بلد الميلاد.');
    if (_birthCountryIsSudan) {
      if (_birthState == null) return (field: null, message: 'اختر ولاية الميلاد.');
    } else if (_birthStateTextController.text.trim().isEmpty) {
      return (field: 'birthStateText', message: 'أدخل ولاية الميلاد.');
    }
    if (_birthCityController.text.trim().isEmpty) {
      return (field: 'birthCity', message: 'أدخل مدينة الميلاد.');
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
      await repository.submitStage3(
        sexDeclared: _sex!,
        ethnicity: _ethnicityController.text.trim(),
        countryOfResidenceCode: _countryOfResidence!.itemCode,
        maritalStatus: _maritalStatus!,
        spouseName: _maritalStatus == 'married' ? _spouseNameController.text.trim() : null,
        hasChildren: _maritalStatus == 'single' ? null : _hasChildren,
        childrenCount: _hasChildren == true ? int.parse(_childrenCountController.text) : null,
        educationLevel: _educationLevel!,
        birthCountryCode: _birthCountry!.itemCode,
        birthStateCode: _birthCountryIsSudan ? _birthState!.itemCode : null,
        birthStateText: _birthCountryIsSudan ? null : _birthStateTextController.text.trim(),
        birthCityText: _birthCityController.text.trim(),
      );
      await repository.advanceToStage('stage4');
      if (!mounted) return;
      context.go('/stage-4');
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

  String _maritalStatusLabel(String status) {
    final isMale = _sex == 'm';
    switch (status) {
      case 'single':
        return isMale ? 'أعزب' : 'عزباء';
      case 'married':
        return isMale ? 'متزوج' : 'متزوجة';
      case 'divorced':
        return isMale ? 'مطلق' : 'مطلقة';
      case 'widowed':
        return isMale ? 'أرمل' : 'أرملة';
      default:
        return status;
    }
  }

  Future<void> _pickCountry({required bool forResidence}) async {
    final items = await ref.read(countryItemsProvider.future);
    if (!mounted) return;
    final selected = await ReferenceItemPickerScreen.open(
      context,
      title: forResidence ? 'المواطنة' : 'بلد الميلاد',
      items: items,
    );
    if (selected == null) return;
    setState(() {
      if (forResidence) {
        _countryOfResidence = selected;
      } else {
        _birthCountry = selected;
        _birthState = null; // country changed — any previously chosen state is now meaningless
      }
    });
    await _saveDraft();
  }

  Future<void> _pickBirthState() async {
    final allAdminDivision = await ref.read(adminDivisionItemsProvider.future);
    if (!mounted) return;
    final states = allAdminDivision
        .where((i) => i.parentCode == _adminDivisionRootCode)
        .toList();
    final selected = await ReferenceItemPickerScreen.open(
      context,
      title: 'ولاية الميلاد',
      items: states,
    );
    if (selected == null) return;
    setState(() => _birthState = selected);
    await _saveDraft();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(title: const Text('البيانات الشخصية والاجتماعية')),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const JourneyProgress(step: JourneyStep.personalData),
                if (widget.offline) const OfflineBanner(),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        // Walk comment 6a. A radio column spends a labelled heading plus one full
                        // row per option on a single answer; as a dropdown the question and its
                        // answer occupy one row. The separate heading goes with it — the field's
                        // own label now carries the question, which is also what makes the
                        // question visually distinct from its answer (general comment 6a).
                        DropdownButtonFormField<String>(
                          initialValue: _sex,
                          // The open menu is the one surface no theme slot reaches; see
                          // `AppTheme.dropdownMenuSurface`.
                          dropdownColor: AppTheme.dropdownMenuSurface(
                            Theme.of(context).colorScheme,
                          ),
                          decoration: const InputDecoration(labelText: 'النوع'),
                          items: const [
                            DropdownMenuItem(value: 'm', child: Text('ذكر')),
                            DropdownMenuItem(value: 'f', child: Text('أنثى')),
                          ],
                          onChanged: (v) {
                            setState(() => _sex = v);
                            _saveDraft();
                          },
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _ethnicityController,
                          focusNode: fieldNode('ethnicity'),
                          textInputAction: fieldAction('ethnicity'),
                          onSubmitted: fieldSubmit('ethnicity'),
                          decoration: fieldDecoration(label: 'الجنس (العرق)', field: 'ethnicity'),
                        ),
                        const SizedBox(height: 16),
                        PickerField(
                          label: 'المواطنة',
                          value: _countryOfResidence?.labelAr,
                          onTap: () => _pickCountry(forResidence: true),
                        ),
                        const SizedBox(height: 16),
                        // Gated on `_sex != null` — found under review, S5-05: every label below
                        // this point (أعزب/عزباء etc., and the spouse-name field's own label)
                        // reads `_sex` to gender itself, and before a sex is chosen there is no
                        // correct gender to assume. Rendering the feminine forms by default
                        // (treating "not yet answered" as "female") contradicts customer.md's own
                        // stated reason sex is asked first: "Arabic gendering ... needs it."
                        // Mirrors the children-block's own `_maritalStatus != null` gate below.
                        if (_sex == null)
                          const Text('اختر النوع أولاً لعرض الحالة الاجتماعية.')
                        else ...[
                          DropdownButtonFormField<String>(
                            initialValue: _maritalStatus,
                            dropdownColor: AppTheme.dropdownMenuSurface(
                              Theme.of(context).colorScheme,
                            ),
                            decoration: const InputDecoration(labelText: 'الحالة الاجتماعية'),
                            items: [
                              for (final status in _maritalStatuses)
                                DropdownMenuItem(
                                  value: status,
                                  // Still gendered off `_sex`, which is why this whole block stays
                                  // behind the `_sex != null` gate above.
                                  child: Text(_maritalStatusLabel(status)),
                                ),
                            ],
                            onChanged: (v) {
                              setState(() {
                                _maritalStatus = v;
                                if (v == 'single') {
                                  _hasChildren = null;
                                  _childrenCountController.clear();
                                }
                              });
                              _saveDraft();
                            },
                          ),
                          if (_maritalStatus == 'married') ...[
                          const SizedBox(height: 8),
                          TextField(
                            controller: _spouseNameController,
                            focusNode: fieldNode('spouseName'),
                            textInputAction: fieldAction('spouseName'),
                            onSubmitted: fieldSubmit('spouseName'),
                            decoration: InputDecoration(
                              labelText: _sex == 'm' ? 'اسم الزوجة' : 'اسم الزوج',
                            ),
                          ),
                        ],
                        if (_maritalStatus != null && _maritalStatus != 'single') ...[
                          const SizedBox(height: 8),
                          const Text('هل لديك أطفال؟'),
                          RadioGroup<bool>(
                            groupValue: _hasChildren,
                            onChanged: (v) {
                              setState(() {
                                _hasChildren = v;
                                if (v != true) _childrenCountController.clear();
                              });
                              _saveDraft();
                            },
                            child: const Column(
                              children: [
                                RadioListTile<bool>(value: true, title: Text('نعم')),
                                RadioListTile<bool>(value: false, title: Text('لا')),
                              ],
                            ),
                          ),
                          if (_hasChildren == true) ...[
                            TextField(
                              controller: _childrenCountController,
                              focusNode: fieldNode('childrenCount'),
                              textInputAction: fieldAction('childrenCount'),
                              onSubmitted: fieldSubmit('childrenCount'),
                              textDirection: TextDirection.ltr,
                              textAlign: TextAlign.right,
                              keyboardType: TextInputType.number,
                              inputFormatters: [
                                const ArabicDigitInputFormatter(),
                                FilteringTextInputFormatter.digitsOnly,
                              ],
                              decoration: const InputDecoration(labelText: 'عدد الأطفال'),
                            ),
                          ],
                        ],
                        ],
                        const SizedBox(height: 16),
                        _EducationLevelList(
                          selected: _educationLevel,
                          onSelected: (level) {
                            setState(() => _educationLevel = level);
                            _saveDraft();
                          },
                        ),
                        const SizedBox(height: 16),
                        PickerField(
                          label: 'بلد الميلاد',
                          value: _birthCountry?.labelAr,
                          onTap: () => _pickCountry(forResidence: false),
                        ),
                        const SizedBox(height: 8),
                        if (_birthCountryIsSudan)
                          PickerField(
                            label: 'ولاية الميلاد',
                            value: _birthState?.labelAr,
                            onTap: _pickBirthState,
                          )
                        else
                          TextField(
                            controller: _birthStateTextController,
                            focusNode: fieldNode('birthStateText'),
                            textInputAction: fieldAction('birthStateText'),
                            onSubmitted: fieldSubmit('birthStateText'),
                            decoration: const InputDecoration(labelText: 'ولاية الميلاد'),
                          ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _birthCityController,
                          focusNode: fieldNode('birthCity'),
                          textInputAction: fieldAction('birthCity'),
                          onSubmitted: fieldSubmit('birthCity', onDone: _onNext),
                          decoration: fieldDecoration(label: 'مدينة الميلاد', field: 'birthCity'),
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
                // No «السابق»: Stage 2 is complete and its codes are consumed, so there is
                // nothing to go back to (customer.md Stage 3 "Exits").
                StageActionBar.previousNext(onNext: _onNext, busy: _submitting),
              ],
            ),
    );
  }
}

class _EducationLevelList extends ConsumerWidget {
  const _EducationLevelList({required this.selected, required this.onSelected});

  final int? selected;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final itemsAsync = ref.watch(educationLevelItemsProvider);
    return itemsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      // Walk comment 2a: naming the list is an implementation detail the customer cannot act
      // on, and it misdirects them toward the field rather than their connection.
      error: (e, _) => const Text('تعذر الاتصال، الرجاء التأكد من الاتصال بالإنترنت ثم أعد المحاولة'),
      data: (items) => DropdownButtonFormField<int>(
        initialValue: selected,
        dropdownColor: AppTheme.dropdownMenuSurface(Theme.of(context).colorScheme),
        decoration: const InputDecoration(labelText: 'المستوى التعليمي'),
        items: [
          for (final item in items)
            DropdownMenuItem(value: int.parse(item.itemCode), child: Text(item.labelAr)),
        ],
        onChanged: (v) {
          if (v != null) onSelected(v);
        },
      ),
    );
  }
}
