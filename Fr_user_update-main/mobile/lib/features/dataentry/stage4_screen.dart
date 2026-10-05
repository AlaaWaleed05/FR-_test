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
import '../../core/reference/reference_providers.dart';
import '../../core/text/arabic_digit_input_formatter.dart';
import '../entry/offline_banner.dart';
import 'reference_item_picker.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 4 — occupation and income (docs/journeys/customer.md). Free back-navigation to
/// Stage 3.
class Stage4Screen extends ConsumerStatefulWidget {
  const Stage4Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage4Screen> createState() => _Stage4ScreenState();
}

class _Stage4ScreenState extends ConsumerState<Stage4Screen>
    with FieldErrorState<Stage4Screen> {
  final _monthlyExpensesController = TextEditingController();
  final _otherTextController = TextEditingController();

  bool _loaded = false;
  bool _submitting = false;

  ReferenceItem? _occupation;
  final Set<String> _selectedIncomeSources = {};
  String? _primaryIncomeSource;

  @override
  void initState() {
    super.initState();
    _load();
    _monthlyExpensesController.addListener(_saveDraft);
    _otherTextController.addListener(_saveDraft);
  }

  @override
  void dispose() {
    _monthlyExpensesController.dispose();
    _otherTextController.dispose();
    disposeFieldNodes();
    super.dispose();
  }

  Future<void> _load() async {
    final repository = ref.read(dataEntryRepositoryProvider);
    unawaited(repository.flushPending());

    final draft = await repository.loadDraft();
    final incomeSources = await repository.loadIncomeSources();
    final occupationItems = await ref.read(occupationItemsProvider.future);
    if (!mounted) return;

    ReferenceItem? occupation;
    if (draft?.occupationCode != null) {
      for (final item in occupationItems) {
        if (item.itemCode == draft!.occupationCode) {
          occupation = item;
          break;
        }
      }
    }

    setState(() {
      _occupation = occupation;
      _monthlyExpensesController.text = draft?.monthlyExpensesSdg ?? '';
      for (final source in incomeSources) {
        _selectedIncomeSources.add(source.code);
        if (source.isPrimary) _primaryIncomeSource = source.code;
        if (source.code == otherIncomeSourceCode) {
          _otherTextController.text = source.otherText ?? '';
        }
      }
      _loaded = true;
    });
  }

  Future<void> _saveDraft() async {
    if (!_loaded) return;
    final repository = ref.read(dataEntryRepositoryProvider);
    await repository.saveDraftFields(
      DataEntryDraftCompanion(
        occupationCode: Value(_occupation?.itemCode),
        monthlyExpensesSdg: Value(_monthlyExpensesController.text.trim()),
      ),
    );
    await repository.saveIncomeSources(
      _selectedIncomeSources
          .map(
            (code) => IncomeSourceEntry(
              code: code,
              primary: code == _primaryIncomeSource,
              otherText: code == otherIncomeSourceCode
                  ? _otherTextController.text.trim()
                  : null,
            ),
          )
          .toList(),
    );
  }

  Future<void> _pickOccupation() async {
    final items = await ref.read(occupationItemsProvider.future);
    if (!mounted) return;
    final selected = await ReferenceItemPickerScreen.open(context, title: 'المهنة', items: items);
    if (selected == null) return;
    setState(() => _occupation = selected);
    await _saveDraft();
  }

  /// D5.6: unchanged rules, unchanged order, unchanged messages — each one now also names the
  /// field it is about, so the message can be shown against that field. A null `field` means the
  /// rule is about a picker or a whole-form condition, which has no `errorText` of its own and
  /// keeps the summary line.
  FieldValidationError? get _validationError {
    if (_occupation == null) return (field: null, message: 'اختر المهنة.');
    if (_selectedIncomeSources.isEmpty) {
      return (field: null, message: 'اختر مصدر دخل واحدًا على الأقل.');
    }
    if (_primaryIncomeSource == null || !_selectedIncomeSources.contains(_primaryIncomeSource)) {
      return (field: null, message: 'حدد مصدر الدخل الأساسي.');
    }
    if (_selectedIncomeSources.contains(otherIncomeSourceCode) &&
        _otherTextController.text.trim().isEmpty) {
      // The "other" box lives inside `_IncomeSourceList`, a separate widget owning its own
      // controller, so this rule keeps the summary line rather than an attached message.
      return (field: null, message: 'حدد مصدر الدخل في حقل "أخرى".');
    }
    if (_monthlyExpensesController.text.trim().isEmpty) {
      return (field: 'monthlyExpenses', message: 'أدخل النفقات الشهرية.');
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
      await repository.submitStage4(
        occupationCode: _occupation!.itemCode,
        incomeSources: _selectedIncomeSources
            .map(
              (code) => IncomeSourceEntry(
                code: code,
                primary: code == _primaryIncomeSource,
                otherText: code == otherIncomeSourceCode
                    ? _otherTextController.text.trim()
                    : null,
              ),
            )
            .toList(),
        monthlyExpensesSdg: _monthlyExpensesController.text.trim(),
      );
      await repository.advanceToStage('stage5');
      if (!mounted) return;
      context.go('/stage-5');
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
      appBar: BrandBanner(title: const Text('المهنة والدخل')),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const JourneyProgress(step: JourneyStep.occupation),
                if (widget.offline) const OfflineBanner(),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        PickerField(
                          label: 'المهنة',
                          value: _occupation?.labelAr,
                          onTap: _pickOccupation,
                        ),
                        const SizedBox(height: 16),
                        // General comment 6a — "every category/question should be evidently
                        // distinctive from its answer choices, and questions visibly separated
                        // from each other, maybe by boxing every question". Where a question
                        // became a dropdown its own field label carries it; this one could not
                        // become a dropdown (see `_IncomeSourceList`), so it gets the boxing
                        // instead — the same white-container section treatment used on Stage 9.
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Text(
                                  'مصدر الدخل',
                                  style: Theme.of(context).textTheme.titleSmall,
                                ),
                                const Text(
                                  'اختر مصدرًا واحدًا أو أكثر، وحدد المصدر الأساسي بالدائرة.',
                                ),
                                _IncomeSourceList(
                                  selected: _selectedIncomeSources,
                                  primary: _primaryIncomeSource,
                                  otherTextController: _otherTextController,
                                  onToggle: (code, checked) {
                                    setState(() {
                                      if (checked) {
                                        _selectedIncomeSources.add(code);
                                      } else {
                                        _selectedIncomeSources.remove(code);
                                        if (_primaryIncomeSource == code) {
                                          _primaryIncomeSource = null;
                                        }
                                      }
                                    });
                                    _saveDraft();
                                  },
                                  onPrimaryChanged: (code) {
                                    setState(() => _primaryIncomeSource = code);
                                    _saveDraft();
                                  },
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        TextField(
                          controller: _monthlyExpensesController,
                          textInputAction: TextInputAction.done,
                          textDirection: TextDirection.ltr,
                          textAlign: TextAlign.right,
                          keyboardType: TextInputType.number,
                          inputFormatters: [
                            const ArabicDigitInputFormatter(),
                            FilteringTextInputFormatter.digitsOnly,
                          ],
                          decoration: const InputDecoration(
                            labelText: 'النفقات الشهرية (جنيه سوداني)',
                            helperText: 'أرقام فقط',
                          ),
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
                  onPrevious: () => context.go('/stage-3'),
                  onNext: _onNext,
                  busy: _submitting,
                ),
              ],
            ),
    );
  }
}

class _IncomeSourceList extends ConsumerWidget {
  const _IncomeSourceList({
    required this.selected,
    required this.primary,
    required this.otherTextController,
    required this.onToggle,
    required this.onPrimaryChanged,
  });

  final Set<String> selected;
  final String? primary;
  final TextEditingController otherTextController;
  final void Function(String code, bool checked) onToggle;
  final ValueChanged<String?> onPrimaryChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final itemsAsync = ref.watch(incomeSourceItemsProvider);
    return itemsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      // Walk comment 2a, applied here at the ONE site that still named its list. The same
      // comment was applied at `account_entry_screen.dart` and `stage3_screen.dart` in batch one
      // and this site was missed: naming which list failed is an implementation detail the
      // customer cannot act on, and it points them at the field instead of at their connection.
      error: (e, _) => const Text(
        'تعذر الاتصال، الرجاء التأكد من الاتصال بالإنترنت ثم أعد المحاولة',
      ),
      // **Walk comment 6a asked for this to become a dropdown. It deliberately does not, and
      // that is a product-owner decision taken 2026-09-08 rather than an omission.**
      //
      // This control carries TWO independent pieces of state: `selected`, a set — the customer
      // may have several income sources — and `primary`, the one they designate as principal.
      // A `DropdownButtonFormField` carries a single value and can express neither. Converting
      // it would silently drop multi-select and the primary designation: a change to what the
      // journey can capture, wearing presentation costume, which is precisely the trap BL-086
      // exists to name. `stage4_screen_test.dart` pins both capabilities so a future conversion
      // cannot pass the gates quietly.
      //
      // What 6a actually complained about — bulk — IS fixed: the rows are compacted and the
      // whole block is boxed as a section by the caller (general comment 6a, "box every
      // question"). Density without capability loss.
      // **The `RadioGroup` scopes each `Radio` individually, NOT the whole list — and that is a
      // defect fix, not a style choice.**
      //
      // Wrapping the `Column` put every `CheckboxListTile` inside the group, and a checked
      // checkbox reports `isChecked` to the semantics tree exactly as a selected radio does. With
      // two income sources ticked, Flutter asserts «Radio groups must not have multiple checked
      // children» on the frame and the screen is dead.
      //
      // **Why it stayed hidden — the real mechanism, corrected by `@agent-reviewer` after a first
      // explanation that blamed visibility and was wrong.** `testWidgets` enables semantics by
      // default and the pre-existing test here already ticks two sources, so both checked tiles
      // were in the tree all along. The gate is `SemanticsNode._addToUpdate`: it opens with
      // `assert(_dirty)` and only then runs the role checks, so a node's role is validated ONLY on
      // an update in which that node is dirty. Ticking a checkbox dirties the TILE, never the
      // enclosing `RadioGroup` — so this tree was built wrong and never looked at. Anything that
      // MOVES the group dirties its geometry and gets it validated.
      //
      // Found 2026-09-10 while adding the journey progress strip, which moved it by 44 dp.
      // Confirmed pre-existing by substituting a plain `SizedBox(height: 44)` for the strip and
      // watching the identical assertion fire — and a larger text scale would do the same.
      //
      // Scoping per radio keeps the behaviour identical — `groupValue`/`onChanged` are shared, so
      // the radios still act as one group — and removes the checkboxes from the group entirely.
      // **The cost, stated because it is a real accessibility loss:** a screen reader no longer
      // announces the radios as one group of N. Filed as BL-130; a proper fix restructures the
      // control so the checkbox and the radio are not nested.
      data: (items) => Column(
          children: [
            for (final item in items)
              CheckboxListTile(
                value: selected.contains(item.itemCode),
                title: Text(item.labelAr),
                // `dense` + compact density is the whole compaction: a full-height ListTile per
                // option is what made a four-or-five-option list dominate the screen. The
                // control affinity is deliberately NOT touched — `secondary` is where the
                // primary-source radio lives, and moving the checkbox would swap the two
                // controls' sides under RTL.
                dense: true,
                visualDensity: VisualDensity.compact,
                contentPadding: EdgeInsets.zero,
                secondary: selected.contains(item.itemCode)
                    ? RadioGroup<String>(
                        groupValue: primary,
                        onChanged: onPrimaryChanged,
                        child: Radio<String>(value: item.itemCode),
                      )
                    : null,
                onChanged: (checked) => onToggle(item.itemCode, checked ?? false),
              ),
            if (selected.contains(otherIncomeSourceCode))
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: TextField(
                  controller: otherTextController,
                  textInputAction: TextInputAction.next,
                  decoration: const InputDecoration(labelText: 'مصادر الدخل'),
                ),
              ),
          ],
        ),
    );
  }
}
