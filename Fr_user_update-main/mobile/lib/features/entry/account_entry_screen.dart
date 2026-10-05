import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/entry_models.dart';
import 'blocked_screen.dart';
import '../../core/entry/entry_providers.dart';

import '../../core/text/arabic_digit_input_formatter.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/screen_title.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 1a — account identification (docs/journeys/customer.md).
class AccountEntryScreen extends ConsumerStatefulWidget {
  const AccountEntryScreen({super.key});

  @override
  ConsumerState<AccountEntryScreen> createState() => _AccountEntryScreenState();
}

class _AccountEntryScreenState extends ConsumerState<AccountEntryScreen> {
  final _accountNumberController = TextEditingController();

  String? _accountNumberError;
  bool _submitting = false;
  bool _unreachable = false;
  bool _draftLoaded = false;

  @override
  void initState() {
    super.initState();
    _loadDraft();
  }

  Future<void> _loadDraft() async {
    final draft = await ref.read(entryRepositoryProvider).loadDraft();
    if (!mounted) return;
    setState(() {
      
      _accountNumberController.text = draft.accountNumber ?? '';
      _draftLoaded = true;
    });
  }

  @override
  void dispose() {
    _accountNumberController.dispose();
    super.dispose();
  }

  Future<void> _saveDraft() async {
    final repository = ref.read(entryRepositoryProvider);
    final current = await repository.loadDraft();
    await repository.saveDraft(
      current.copyWith(
        
        accountNumber: _accountNumberController.text,
      ),
    );
  }

  Future<void> _submit() async {
   
    final accountNumber = _accountNumberController.text.trim();
    if (accountNumber.isEmpty) return;

    setState(() {
      _submitting = true;
      _accountNumberError = null;
      _unreachable = false;
    });

    try {
      final result = await ref.read(entryRepositoryProvider).checkAccount(accountNumber);
      if (!mounted) return;
      switch (result.continuation) {
        case AccountContinuation.proceed:
          context.go('/contact-channels');
        case AccountContinuation.retry:
          setState(() => _accountNumberError = 'تعذر العثور على هذا الحساب. يرجى التحقق من الرقم.');
        case AccountContinuation.terminal:
          // customer.md Stage 0: a "complete"/terminal answer clears local state before showing
          // the terminal screen — this reaches the same outcome from Stage 1a itself and must
          // clear the same way, or a stale branch/account (and, for a resumed draft, phone/email)
          // stays prefilled for the next customer on a shared device (found under review, S5-02).
          await ref.read(entryRepositoryProvider).abandon();
          if (!mounted) return;
          // BL-123, and it mirrors `EntryRepository`'s resume path exactly — the two must not
          // drift, because they are the same decision reached from two entry points. An inactive
          // ACCOUNT keeps its own true message; a finished PROFILE goes to the screen that owns
          // the only sentence true for all four terminal statuses.
          if (result.outcome == AccountOutcome.inactive) {
            context.go('/terminal', extra: 'الحساب غير نشط. يرجى مراجعة أقرب فرع.');
          } else {
            context.go('/ended');
          }
        case AccountContinuation.blocked:
          // BL-021. Before S8-14 this value threw out of the decode and landed in the bare
          // `catch` below, which sets `_accountNumberError` — so a phone lock was reported to the
          // customer as a problem with their ACCOUNT NUMBER, the one field that was not at fault.
          // No `abandon()` here: unlike the terminal branch this block is temporary and must not
          // cost the customer their draft.
          context.go(
            '/blocked',
            extra: BlockedArgs(
              blockedUntil: result.blockedUntil,
              // This session has entered nothing yet, so the "everything you entered is saved"
              // reassurance would be noise here even though it is true one screen later.
              progressPreserved: false,
            ),
          );
      }
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => _unreachable = true);
    } catch (_) {
      // An unmapped failure (anything that is not a connection error or, since S3-02, a 5xx --
      // those become BackendUnreachableException above) must still leave the customer with
      // something to read, not a silently re-enabled button with no explanation (found under
      // review, S5-02).
      if (!mounted) return;
      setState(() => _accountNumberError = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {


    // **Walk comment 2c, and it is a DEFECT rather than a preference.** When a reference list
    // failed, the old screen swapped only the branch picker for an error line and left the
    // account-number field and the Next button live underneath it — a half-usable screen where
    // the customer can type an account number and press a button that cannot possibly work.
    //
    // §2.6 of the walk report sharpens this: the reference endpoint answered HTTP 200 in 0.259 s
    // throughout, and the failure was the handset's own connectivity (captured as
    // `OBTAINING_IPADDR`). So this is not a rare server fault — it is the ordinary condition of a
    // customer on poor connectivity, which is most of this market.
    //
    // The launch screen already solved the sibling problem one screen earlier (D7.5/D8.3, keeping
    // raw exception text away from the customer); this is the same class of issue, one screen on.


    return Scaffold(
      appBar: BrandBanner(title: const ScreenTitle('بيانات الحساب')),
      // Walk comment 9 (walk of 2026-09-10). Wrapped at the BODY, not at the action row, because
      // this screen has three branches and only ONE of them ends in a `StageActionBar`: the
      // `listsUnavailable` branch is a centred block whose retry `FilledButton` is the only
      // control on the screen, and insetting the action row alone left it uncovered. Found by
      // `@agent-reviewer`, which noted the first pass applied this very reasoning to
      // `terminal_screen.dart` and then missed the identical case here.
      //
      // Nesting is safe: `SafeArea` wraps its child in `MediaQuery.removePadding`, so the inner
      // `StageActionBar` sees a zero inset and becomes a no-op rather than double-counting.
      body: SafeArea(
        top: false,
        child: !_draftLoaded
            ? const Center(child: CircularProgressIndicator())
            
            : Column(
              children: [
                // Walk comments 1/5 (2026-09-10). The loaded branch ONLY — this screen is the
                // single exception to `JourneyProgress`'s own show/hide rule, and the reason is
                // written there rather than here so the rule lives in one place: Stage 1a can
                // fail to load at all, and «الخطوة ١ من ١٣» over a screen that cannot proceed is
                // a claim about progress that has not been made.
                const JourneyProgress(step: JourneyStep.accountEntry),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                  // **Walk comment 3a** — "this screen doesn't have much detail, make use of the
                  // screen and don't push them both on top and leave the rest empty". Three
                  // presentation changes, no journey or validation change:
                  //  1. a leading line saying what the screen is for, the same shape comment 4b
                  //     asked for on the channels screen;
                  //  2. the two inputs grouped into one white container on the tinted canvas, so
                  //     the sparse content reads as a deliberate section rather than as two
                  //     stranded fields — the same section treatment 10c uses on Stage 9;
                  //  3. the content moved into an `Expanded`/`SingleChildScrollView` with the
                  //     action button pinned BELOW it as the Column's own last child, instead of
                  //     the button floating directly under the last field. That is what actually
                  //     removes the empty space, and it also puts this screen's button where the
                  //     identity screens already put theirs — the consistent position general
                  //     comment 5 asked for, delivered here rather than app-wide (a button THEME
                  //     is the app-wide half, and is filed, not built). See the note on that
                  //     button below for why it is NOT a `Spacer`.
                        const Text('أدخل رقم حسابك للبدء.'),
                        const SizedBox(height: 16),
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                
                                TextField(
                                  controller: _accountNumberController,
                                  textInputAction: TextInputAction.done,
                                  textDirection: TextDirection.ltr,
                                  textAlign: TextAlign.right,
                                  keyboardType: TextInputType.number,
                                  inputFormatters: [
                                    const ArabicDigitInputFormatter(),
                                    FilteringTextInputFormatter.digitsOnly,
                                  ],
                                  decoration: InputDecoration(
                                    labelText: 'رقم الحساب',
                                    errorText: _accountNumberError,
                                  ),
                                  onChanged: (_) {
                                    setState(() => _accountNumberError = null);
                                    _saveDraft();
                                  },
                                ),
                              ],
                            ),
                          ),
                        ),
                        if (_unreachable) ...[
                          const SizedBox(height: 12),
                          const _InlineConnectivityError(
                            'تعذر الاتصال، الرجاء التأكد من الاتصال بالإنترنت ثم أعد المحاولة',
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                // Pinned OUTSIDE the scroll view rather than pushed down inside it. A `Spacer`
                // would have anchored the button to the bottom too, but in a non-scrolling column
                // it overflows the moment the keyboard takes half the viewport — the same class
                // of defect batch one hit on Stage 12, on a screen whose button must never be
                // unreachable. This is the pattern the OTP and data-entry screens already use.
                StageActionBar.previousNext(
                  onNext:
                      
                      _accountNumberController.text.trim().isEmpty
                      ? null
                      : _submit,
                  busy: _submitting,
                ),
              ],
            ),
      ),
    );
  }
}

/// Walk comments 2a and 2b. The whole screen, because 2c says a screen that cannot work must not
/// offer inputs that look as though they can.
///
/// **2a: it does not name WHICH list failed.** The old copy said «تعذر تحميل قائمة الفروع» — the
/// branch list is an implementation detail the customer has no use for, and naming it invites them
/// to think the branch field specifically is at fault rather than their connection.




/// The same treatment for the inline case, where the lists loaded but the account check itself
/// could not reach the backend. Emphasis and the connectivity icon (2b) without taking the screen
/// — here the customer's input IS still valid and worth keeping, so 2c does not apply.
class _InlineConnectivityError extends StatelessWidget {
  const _InlineConnectivityError(this.message);

  final String message;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.wifi_off_outlined, size: 20, color: theme.colorScheme.error),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            message,
            style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.error),
          ),
        ),
      ],
    );
  }
}


