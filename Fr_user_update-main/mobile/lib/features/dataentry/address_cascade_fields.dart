import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/database/reference_database.dart';
import '../../core/reference/reference_providers.dart';
import 'reference_item_picker.dart';

/// The country → state → locality cascade shared by Stage 5 (home address) and Stage 6 (work
/// address) — customer.md: "cascading pick-lists, each populating the next," with a free-text
/// fallback for any country other than Sudan ("Addresses outside Sudan": "not a rare case").
///
/// Stateless by design: the parent screen owns the selected [country]/[stateItem]/[localityItem]
/// (needed for its own validation and draft-saving), and must clear [localityItem] in
/// [onStateChanged] when the state changes, and clear both [stateItem]/[localityItem] in
/// [onCountryChanged] when the country changes — "changing state clears any previously selected
/// locality" is the caller's responsibility, not this widget's, exactly like `Stage3Screen`'s own
/// birth-country/birth-state clearing.
class AddressCascadeFields extends ConsumerWidget {
  const AddressCascadeFields({
    super.key,
    required this.country,
    required this.stateItem,
    required this.localityItem,
    required this.stateTextController,
    required this.localityTextController,
    required this.adminDivisionRootCode,
    required this.onCountryChanged,
    required this.onStateChanged,
    required this.onLocalityChanged,
    this.countryLabel = 'البلد',
    this.stateLabel = 'الولاية',
    this.localityLabel = 'المحلية / المحافظة',
    this.stateTextFocus,
    this.localityTextFocus,
    this.stateTextAction,
    this.localityTextAction,
    this.onStateTextSubmitted,
    this.onLocalityTextSubmitted,
  });

  final ReferenceItem? country;
  final ReferenceItem? stateItem;
  final ReferenceItem? localityItem;
  final TextEditingController stateTextController;
  final TextEditingController localityTextController;
  final String? adminDivisionRootCode;
  final ValueChanged<ReferenceItem> onCountryChanged;
  final ValueChanged<ReferenceItem?> onStateChanged;
  final ValueChanged<ReferenceItem?> onLocalityChanged;
  final String countryLabel;
  final String stateLabel;
  final String localityLabel;

  /// **Keyboard-chain wiring for the two NON-SUDAN free-text fields (walk comment 2, 2026-09-10).**
  /// Supplied by the parent screen, because the order these fields sit in belongs to the screen's
  /// `textFieldOrder`, not to this widget.
  ///
  /// Found by `@agent-reviewer`: the first version of the chain left these out entirely, so a
  /// customer with a foreign address had the keyboard jump straight from the employer/city field
  /// over two MANDATORY fields. Worse than a skip — they also had no `fieldNode`, so when the
  /// customer then pressed Next, `showFieldError` could neither focus them nor render the summary
  /// line, and Next appeared to do nothing at all. That is the very symptom walk comment 2 reports.
  ///
  /// Null on a screen that has not opted in, which leaves the previous behaviour untouched.
  final FocusNode? stateTextFocus;
  final FocusNode? localityTextFocus;
  final TextInputAction? stateTextAction;
  final TextInputAction? localityTextAction;
  final ValueChanged<String>? onStateTextSubmitted;
  final ValueChanged<String>? onLocalityTextSubmitted;

  bool get isSudan => country != null && country!.itemCode == adminDivisionRootCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PickerField(
          label: countryLabel,
          value: country?.labelAr,
          onTap: () async {
            final items = await ref.read(countryItemsProvider.future);
            if (!context.mounted) return;
            final selected = await ReferenceItemPickerScreen.open(
              context,
              title: countryLabel,
              items: items,
            );
            if (selected != null) onCountryChanged(selected);
          },
        ),
        const SizedBox(height: 8),
        if (isSudan)
          PickerField(
            label: stateLabel,
            value: stateItem?.labelAr,
            onTap: () async {
              final all = await ref.read(adminDivisionItemsProvider.future);
              if (!context.mounted) return;
              final states = all.where((i) => i.parentCode == adminDivisionRootCode).toList();
              final selected = await ReferenceItemPickerScreen.open(
                context,
                title: stateLabel,
                items: states,
              );
              if (selected != null) onStateChanged(selected);
            },
          )
        else
          TextField(
            controller: stateTextController,
            focusNode: stateTextFocus,
            textInputAction: stateTextAction ?? TextInputAction.next,
            onSubmitted: onStateTextSubmitted,
            decoration: InputDecoration(labelText: stateLabel),
          ),
        const SizedBox(height: 8),
        if (isSudan)
          PickerField(
            label: localityLabel,
            value: localityItem?.labelAr,
            onTap: stateItem == null
                ? null
                : () async {
                    final all = await ref.read(adminDivisionItemsProvider.future);
                    if (!context.mounted) return;
                    final localities = all
                        .where((i) => i.parentCode == stateItem!.itemCode)
                        .toList();
                    final selected = await ReferenceItemPickerScreen.open(
                      context,
                      title: localityLabel,
                      items: localities,
                    );
                    if (selected != null) onLocalityChanged(selected);
                  },
          )
        else
          TextField(
            controller: localityTextController,
            focusNode: localityTextFocus,
            textInputAction: localityTextAction ?? TextInputAction.next,
            onSubmitted: onLocalityTextSubmitted,
            decoration: InputDecoration(labelText: localityLabel),
          ),
      ],
    );
  }
}
