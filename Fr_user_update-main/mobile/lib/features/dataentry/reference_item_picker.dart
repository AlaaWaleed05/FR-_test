import 'package:flutter/material.dart';

import '../../core/database/reference_database.dart';
import '../../core/text/arabic_fold.dart';
import '../../core/widgets/brand_banner.dart';

/// One reusable full-screen search-first picker for every reference-list selection in stages 3-6
/// (customer.md's occupation-picker spec, applied to every call site — AD-006's own "one bespoke
/// page beats six configurations" reasoning, already the stated reason `dropdown_search` was
/// rejected). Reused for occupation, country (×4), and admin_division state/locality (×5) — see
/// each stage screen for its call sites.
///
/// Takes an already-loaded [items] list (the caller already holds it via a `StreamProvider` watch)
/// — no internal async, so this stays a pure, easily widget-tested screen. Items are shown in the
/// given order (already `sort_ordinal`, server-computed — never re-sorted here). The item code is
/// never displayed, only labels, and the query is folded via [arFold] and matched as a substring
/// against the item's pre-folded `searchAr`/`searchEn` (reference-data.md client rule 6: the device
/// folds only the query, labels arrive already folded).
class ReferenceItemPickerScreen extends StatefulWidget {
  const ReferenceItemPickerScreen({super.key, required this.title, required this.items});

  final String title;
  final List<ReferenceItem> items;

  /// Pushes the picker and returns the selected item, or `null` if the customer backed out with
  /// nothing chosen.
  static Future<ReferenceItem?> open(
    BuildContext context, {
    required String title,
    required List<ReferenceItem> items,
  }) {
    return Navigator.of(context).push<ReferenceItem>(
      MaterialPageRoute(builder: (_) => ReferenceItemPickerScreen(title: title, items: items)),
    );
  }

  @override
  State<ReferenceItemPickerScreen> createState() => _ReferenceItemPickerScreenState();
}

/// A read-only field that opens [ReferenceItemPickerScreen.open] on tap, shared by every stage
/// 3-6 screen's picker call sites (occupation, country ×4, admin_division state/locality ×5).
class PickerField extends StatelessWidget {
  const PickerField({super.key, required this.label, required this.value, required this.onTap});

  final String label;
  final String? value;

  /// `null` disables the field (e.g. a locality picker before any state is chosen).
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: label,
          suffixIcon: const Icon(Icons.arrow_drop_down),
        ),
        child: Text(value ?? ''),
      ),
    );
  }
}

class _ReferenceItemPickerScreenState extends State<ReferenceItemPickerScreen> {
  final _searchController = TextEditingController();
  final _searchFocusNode = FocusNode();
  String _foldedQuery = '';

  @override
  void initState() {
    super.initState();
    // "Opens with the search field focused and the keyboard already up" (customer.md's occupation
    // picker spec).
    WidgetsBinding.instance.addPostFrameCallback((_) => _searchFocusNode.requestFocus());
    _searchController.addListener(() {
      setState(() => _foldedQuery = arFold(_searchController.text));
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    _searchFocusNode.dispose();
    super.dispose();
  }

  List<ReferenceItem> get _filtered {
    if (_foldedQuery.isEmpty) return widget.items;
    return widget.items.where((item) {
      if (item.searchAr.contains(_foldedQuery)) return true;
      final searchEn = item.searchEn;
      return searchEn != null && searchEn.contains(_foldedQuery);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _filtered;
    return Scaffold(
      appBar: BrandBanner(title: Text(widget.title)),
      // Walk comment 9 (walk of 2026-09-10): the last row of a long list — 138 occupations —
      // sat under the system navigation bar and could not be tapped.
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: TextField(
                controller: _searchController,
                focusNode: _searchFocusNode,
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  labelText: 'بحث',
                ),
              ),
            ),
            Expanded(
              child: filtered.isEmpty
                  ? const Center(
                      child: Padding(
                        padding: EdgeInsets.all(24),
                        child: Text('لا توجد نتائج. جرّب كلمة أقصر.', textAlign: TextAlign.center),
                      ),
                    )
                  : ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (context, index) {
                        final item = filtered[index];
                        return ListTile(
                          title: Text(item.labelAr),
                          subtitle: item.labelEn == null ? null : Text(item.labelEn!),
                          onTap: () => Navigator.of(context).pop(item),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
