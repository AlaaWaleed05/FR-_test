import 'dart:async' show unawaited;

import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/dataentry/data_entry_models.dart';
import '../../core/dataentry/data_entry_providers.dart';
import '../../core/database/session_database.dart' show DataEntryDraftCompanion;
import '../../core/entry/entry_models.dart'
    show IdentityScanStage, ProfileAlreadyCompleteException;
import '../../core/identityscan/identity_scan_models.dart';
import '../entry/offline_banner.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 7 — identity document type (docs/journeys/customer.md). One field, and the last
/// stage with free back-navigation.
///
/// **No Uqudo token is requested here.** customer.md is explicit about it and about why: the token
/// lives ~1800 s, and a customer who reaches this screen and then puts the phone down for half an
/// hour would arrive at the scan holding a dead one — a failure that looks like the app breaking.
/// The token is minted at the moment the customer taps to scan, on Stage 8.
///
/// **This screen is the boundary** (customer.md Stage 7's closing note). Before it, every stage is
/// offline-capable and freely revisited; after it, each step consumes a real Uqudo operation. That
/// is why this is the one data-entry screen that does not advance on a queued submission — see
/// [_onNext].
class Stage7Screen extends ConsumerStatefulWidget {
  const Stage7Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage7Screen> createState() => _Stage7ScreenState();
}

class _Stage7ScreenState extends ConsumerState<Stage7Screen> {
  bool _loaded = false;
  bool _submitting = false;
  String? _errorMessage;

  String? _identityType;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final repository = ref.read(dataEntryRepositoryProvider);
    unawaited(repository.flushPending());

    final draft = await repository.loadDraft();
    if (!mounted) return;
    setState(() {
      _identityType = draft?.identityType;
      _loaded = true;
    });
  }

  Future<void> _saveDraft() async {
    if (!_loaded) return;
    await ref
        .read(dataEntryRepositoryProvider)
        .saveDraftFields(DataEntryDraftCompanion(identityType: Value(_identityType)));
  }

  /// **Unreachable as a UI state since walk comment 4 (2026-09-10), and kept deliberately.**
  /// `_onNext` has exactly one caller, `_selectType`, which assigns `_identityType` immediately
  /// before calling it — so «اختر نوع وثيقة الهوية.» can no longer render. What the check still
  /// earns its place for is the `_identityType!` in `_onNext`: this is what makes that `!` safe.
  /// Noted rather than deleted because a reader finding an uncovered branch should learn why it
  /// is here, not delete it and re-introduce a null assertion with nothing behind it.
  String? get _validationError {
    if (_identityType == null) return 'اختر نوع وثيقة الهوية.';
    return null;
  }

  Future<void> _onNext() async {
    final validationError = _validationError;
    if (validationError != null) {
      setState(() => _errorMessage = validationError);
      return;
    }

    // `_submitting` is already true — `_selectType` raises it before its awaited draft write, so
    // that the window between the tap and this point is covered too. Kept as an assignment rather
    // than removed so `_onNext` stays correct if it ever gains a second caller.
    setState(() {
      _errorMessage = null;
      _submitting = true;
    });

    final repository = ref.read(dataEntryRepositoryProvider);
    try {
      final landed = await repository.submitStage7(identityType: _identityType!);
      if (!mounted) return;
      if (!landed) {
        // The one place this app deliberately diverges from the stage 3-6 pattern, which discards
        // this return value and advances regardless. customer.md Stage 7's "Exits": "Backend
        // unreachable → the entered data is on the device and queued. The customer is told a
        // connection is needed to continue". Stage 8 opens the Uqudo SDK, which needs the network
        // to upload its capture and a backend that can reach Uqudo to verify it — walking the
        // customer forward into that would produce a failure at the worst possible moment, one
        // screen after the point where attempts start costing them something.
        //
        // Nothing is lost by stopping here: the submission is queued and lands on the next
        // opportunity, and the selection is already in the draft.
        setState(() {
          _errorMessage =
              'تم حفظ اختيارك على الجهاز. يلزم الاتصال بالإنترنت لمتابعة مسح الوثيقة. '
              'يرجى المحاولة مرة أخرى عند توفر الاتصال.';
        });
        return;
      }
      await repository.advanceToStage(IdentityScanStage.stage8.name);
      if (!mounted) return;
      context.go('/stage-8');
    } on ProfileAlreadyCompleteException {
      if (!mounted) return;
      context.go('/ended');
    } on DataEntryRejectedException {
      if (!mounted) return;
      setState(() => _errorMessage = 'تعذر حفظ البيانات. يرجى مراجعة الحقول والمحاولة مرة أخرى.');
    } catch (_) {
      if (!mounted) return;
      setState(() => _errorMessage = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// **Tapping a card SELECTS AND ADVANCES** — walk comment 4 of the 2026-09-10 walk, settled by
  /// the product owner. This reverses the rule the previous version of this file argued for (see
  /// the card block in `build`); the reasoning it gave is answered rather than ignored, and the
  /// answer is written down below.
  ///
  /// `await _saveDraft()` before submitting, NOT the old fire-and-forget. The two calls used to be
  /// separate user actions with a screen's worth of time between them; back-to-back they race, and
  /// `_flushPending` rebuilds a queued Stage 7 from `draft.identityType!`
  /// (`data_entry_repository.dart:562`) — a non-null assertion that would throw on a draft row
  /// whose write had not landed yet. `submitStage7` itself takes the type as a parameter and is
  /// unaffected; the ordering is for the queue path.
  Future<void> _selectType(String value) async {
    // **`_submitting` is raised HERE, not inside `_onNext`.** The first version raised it in
    // `_onNext`, which runs only AFTER the awaited draft write below — leaving a window in which
    // a second tap passed both this early return and `build`'s `onTap: null`, because the two
    // "independent" guards read the same latch. Found by `@agent-reviewer`.
    //
    // The window is real on a device, not just in theory: the session database is opened with
    // `NativeDatabase.createInBackground`, so every draft write is an isolate round trip and
    // pointer events are delivered inside it. The damage was worse than a duplicate POST —
    // `_identityType` is reassigned by the second tap before the first submission reads it, so
    // submission #1 could carry the SECOND tap's document type and send the customer to a scan
    // for a document they did not choose last.
    if (_submitting) return;
    setState(() {
      _identityType = value;
      _errorMessage = null;
      _submitting = true;
    });

    // **Never let a local write strand the screen.** The card is the only forward control now,
    // so an exception escaping here would leave no navigation, no spinner and no message — a
    // dead screen. The submission does not depend on this row (`submitStage7` takes the type as
    // a parameter), so a failed save falls through and the customer still advances; only the
    // offline-queue rebuild would be affected, and that path re-reads the draft anyway.
    try {
      await _saveDraft();
    } catch (_) {
      // Deliberately swallowed — see above. Nothing actionable to show the customer.
    }
    if (!mounted) return;
    await _onNext();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(title: const Text('نوع وثيقة الهوية')),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const JourneyProgress(step: JourneyStep.documentType),
                if (widget.offline) const OfflineBanner(),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Text(
                          'اختر الوثيقة التي ستستخدمها لإثبات هويتك. سيتم مسحها ضوئيًا في '
                          'الخطوة التالية، ولن تحتاج إلى إدخال أي من بياناتها يدويًا.',
                        ),
                        const SizedBox(height: 16),
                        // The two values are the journey's own vocabulary and go on the wire
                        // exactly as written (`DataEntryService.IDENTITY_TYPES`). Uqudo's
                        // `PASSPORT`/`SDN_ID` never appear here — that translation happens once,
                        // inside `PluginUqudoScanner`.
                        // Walk comment 7a: with only two options, a radio list wastes the screen
                        // and buries the choice in a control. These are cards, side by side, and
                        // the WHOLE card is the tap target rather than a small radio dot.
                        //
                        // **Tapping a card now ADVANCES (walk comment 4, 2026-09-10).** This
                        // REVERSES what this comment said until then — that the card only selects
                        // and the Next button advances, because "making it navigate would remove
                        // the customer's chance to change their mind before committing to a
                        // document type, and the per-type scan budget (BL-039, five attempts each)
                        // makes that choice expensive to get wrong."
                        //
                        // That concern was real and is ANSWERED, not overruled: Stage 8 opens on
                        // its preparation view, which already carries «تغيير الوثيقة» returning
                        // here (`stage8_screen.dart`, `_changeDocument`), and offers it again on the rejected
                        // view. So a mis-tap costs a screen transition and no scan attempt — the
                        // budget is spent by launching the SDK, not by arriving at Stage 8. The
                        // chance to change their mind moved one screen later; it did not go away.
                        //
                        // Two taps become one for every customer who gets it right first time,
                        // which is the overwhelming majority of them.
                        // IntrinsicHeight, because `CrossAxisAlignment.stretch` inside a scroll
                        // view asks the children for an INFINITE height and the layout asserts.
                        // It is what makes the two cards match height when one label wraps and
                        // the other does not.
                        IntrinsicHeight(
                          child: Row(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Expanded(
                              child: _DocumentTypeCard(
                                value: IdentityDocumentTypes.passport,
                                icon: Icons.menu_book_outlined,
                                label: 'جواز السفر',
                                selected: _identityType == IdentityDocumentTypes.passport,
                                busy:
                                    _submitting &&
                                    _identityType == IdentityDocumentTypes.passport,
                                onTap: _submitting
                                    ? null
                                    : () => _selectType(IdentityDocumentTypes.passport),
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: _DocumentTypeCard(
                                value: IdentityDocumentTypes.nationalId,
                                icon: Icons.badge_outlined,
                                // Walk comment 8 (2026-09-10). «الرقم الوطني» is the NUMBER
                                // printed on the card, not the card — naming the document after
                                // its own data field. The bank's own term for the document is
                                // «البطاقة القومية». Two other sites follow in the same commit:
                                // Stage 8's `_documentLabel`, and `uq_sdn_id_description` in
                                // `android/.../values-ar/strings.xml`, which the Uqudo SDK prints
                                // on its own camera screen seconds later. Stage 9's two
                                // «الرقم الوطني» labels do NOT change — there the string genuinely
                                // means the number. That is the complete list; it was two thirds
                                // complete until `@agent-reviewer` found the SDK strings.
                                label: 'البطاقة القومية',
                                selected: _identityType == IdentityDocumentTypes.nationalId,
                                busy:
                                    _submitting &&
                                    _identityType == IdentityDocumentTypes.nationalId,
                                onTap: _submitting
                                    ? null
                                    : () => _selectType(IdentityDocumentTypes.nationalId),
                              ),
                            ),
                          ],
                          ),
                        ),
                        const SizedBox(height: 16),
                        if (_errorMessage != null)
                          Text(
                            _errorMessage!,
                            style: TextStyle(color: Theme.of(context).colorScheme.error),
                          ),
                      ],
                    ),
                  ),
                ),
                // **«التالي» is gone; «السابق» stays.** The card IS the forward action now, so a
                // Next button would be a second way to do the same thing — and, worse, one that
                // could be pressed with no card chosen, which is the only path that ever produced
                // `_validationError`. Back still has to be offered: Stage 7 is the last stage
                // with free back-navigation (customer.md Stage 7, "this is the boundary").
                StageActionBar(
                  child: OutlinedButton(
                    onPressed: _submitting ? null : () => context.go('/stage-6'),
                    child: const Text('السابق'),
                  ),
                ),
              ],
            ),
    );
  }
}


/// One of the two identity-document options (walk comment 7a).
///
/// **The icon is a placeholder for artwork that does not exist yet, and deliberately so.** 7a asks
/// for a picture of each document. CLAUDE.md forbids identity-document images anywhere in this
/// repository, so a photograph of a real passport or national ID card can never be the answer here
/// — any future asset has to be a GENERIC illustration drawn for the purpose. Until one is
/// commissioned, a large Material glyph carries the same "this is the passport one" recognition
/// without pretending to be a document. Filed rather than faked.
class _DocumentTypeCard extends StatelessWidget {
  const _DocumentTypeCard({
    required this.value,
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
    this.busy = false,
  });

  final String value;
  final IconData icon;
  final String label;
  final bool selected;

  /// True on the card that was tapped, while its submission is in flight. Since the card is now
  /// the forward action, this is the only place a customer can see that anything is happening —
  /// the «التالي» button that used to carry the spinner is gone.
  final bool busy;

  /// `null` while any submission is in flight, which is what stops a second card being tapped
  /// mid-flight and submitting a different type over the first.
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // A RadioListTile announced its own selected state to a screen reader for free; a Card does
    // not, so replacing one with the other would have silently dropped that. `selected` restores
    // it and `button` says the whole card is the action.
    return Semantics(
      selected: selected,
      button: true,
      child: Card(
      key: ValueKey('doc_card_$value'),
      // The card's own fill comes from `cardTheme`; only the selected state is named here, so the
      // surface treatment stays a theme decision.
      color: selected ? theme.colorScheme.primaryContainer : null,
      // The UNSELECTED border is `outline`, not `outlineVariant`, and that is the accessibility
      // rule the theme already applies to every resting text field — not a style preference.
      // Measured on the resolved palette: `outlineVariant` (#C4C6CF) is 1.39:1 against the
      // tinted canvas (#E7E8EE); `outline` (#74777F) is 3.66:1, clearing WCAG 1.4.11's 3:1 for
      // the visual information that identifies a control. `app_theme.dart` calls that hairline
      // load-bearing rather than decorative and `app_theme_test.dart` enforces it for fields.
      //
      // This screen is the one place where a CONTAINER IS THE CONTROL (comment 7a: the card
      // overall is the action), so exempting it was the inconsistency — the rule applied
      // everywhere except where a container had become the button. Honest caveat: the card also
      // carries a 48px glyph and a label, so it was never as bare as an empty input; the
      // argument for changing it is internal consistency more than an outright 1.4.11 failure.
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: selected ? theme.colorScheme.primary : theme.colorScheme.outline,
          width: selected ? 2 : 1,
        ),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // Same 48 dp box either way, so the card does not resize when it starts working.
              SizedBox(
                width: 48,
                height: 48,
                child: busy
                    ? const Center(
                        child: SizedBox(
                          width: 24,
                          height: 24,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      )
                    : Icon(icon, size: 48, color: theme.colorScheme.primary),
              ),
              const SizedBox(height: 12),
              Text(
                label,
                textAlign: TextAlign.center,
                style: theme.textTheme.titleSmall,
              ),
            ],
          ),
        ),
      ),
      ),
    );
  }
}
