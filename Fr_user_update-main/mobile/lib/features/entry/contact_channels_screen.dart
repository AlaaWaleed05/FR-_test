import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/entry_models.dart';
import '../../core/entry/channel_labels.dart';
import '../../core/widgets/screen_title.dart';
import '../../core/entry/entry_providers.dart';
import '../../core/text/arabic_digit_input_formatter.dart';
import 'offline_banner.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/text/ltr_value.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 1b — contact channels (docs/journeys/customer.md). Reached either fresh from
/// Stage 1a (an active, non-terminal account just passed `account-check`) or resumed by
/// [LaunchScreen] for an account whose 1b draft was never submitted.
class ContactChannelsScreen extends ConsumerStatefulWidget {
  const ContactChannelsScreen({super.key, this.offline = false});

  /// Whether this resume could not be confirmed with the backend (customer.md Stage 0). `false`
  /// when reached fresh from Stage 1a, since that check just succeeded online.
  final bool offline;

  @override
  ConsumerState<ContactChannelsScreen> createState() => _ContactChannelsScreenState();
}

/// A Sudan local mobile number: the trunk `0` plus the nine digits the backend's
/// `PhoneNumberNormalizer.SUDAN_LOCAL_DIGITS_AFTER_ZERO` expects. Named rather than written as
/// `10` at the two sites that need it, so the cap and the hint cannot drift apart.
const int _localPhoneDigits = 10;

class _ContactChannelsScreenState extends ConsumerState<ContactChannelsScreen> {
  // Walk comment 2: the phone field hands the keyboard to the email field rather than to a
  // checkbox. Disposed alongside the controllers below.
  final _phoneFocus = FocusNode();
  final _emailFocus = FocusNode();

  final _phoneController = TextEditingController();
  final _emailController = TextEditingController();
  bool _smsSelected = true;
  // Consistency with EntryDraft's default. Note this initializer is never what a customer sees:
  // `_load` overwrites it from the draft, and the body is gated on `_draftLoaded`.
  bool _whatsappSelected = false;
  bool _emailSelected = true;
  bool _draftLoaded = false;
  bool _submitting = false;
  String? _errorMessage;

  /// Set the moment `_abandon()` starts, checked by `_saveDraft()` before every write. Closes a
  /// race found under review (S5-02, second pass): `_saveDraft()` is async and reads-then-writes
  /// `LocalDraft`; a save already in flight when Abandon is tapped could otherwise complete AFTER
  /// `abandon()`'s `clear()`, resurrecting a row with the just-abandoned customer's data for
  /// whoever uses the device next.
  bool _abandoned = false;

  /// The exact branch/account pair Stage 1a's `account-check` last validated
  /// (`EntryRepository.verifiedAccount()`) — deliberately NOT read from `LocalDraft`, which the
  /// customer could in principle have gone back and edited without re-running Stage 1a. Found
  /// under review, S5-02.
  String? _branchCode;
  String? _accountNumber;

  /// Tracks whether the email field was non-empty as of the last edit, so the listener below can
  /// detect an empty→non-empty transition and re-activate `_emailSelected` — see that listener's
  /// own comment.
  bool _wasEmailProvided = false;

  bool get _bothPhoneChannelsDeselected => !_smsSelected && !_whatsappSelected;
  bool get _emailProvided => _emailController.text.trim().isNotEmpty;
  bool get _phoneProvided => _phoneController.text.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Sets the controllers' initial text and only THEN attaches the autosave listeners — found
  /// under review (S5-02, second pass): attaching them in `initState` meant `_load()`'s own
  /// `.text = draft.phoneNumber` assignment (setting the pre-fill) fired the listener too,
  /// `_saveDraft()`'d straight after `_load()` had just read the same values, and — if Abandon
  /// was tapped while `_load()` was still in flight — could write the just-cleared draft right
  /// back with the abandoned customer's own data. Attaching listeners only after the initial
  /// text is set means a programmatic pre-fill never counts as an edit.
  Future<void> _load() async {
    final repository = ref.read(entryRepositoryProvider);
    final draft = await repository.loadDraft();
    final verified = await repository.verifiedAccount();
    if (!mounted || _abandoned) return;
    setState(() {
      _branchCode = verified?.branchCode;
      _accountNumber = verified?.accountNumber;
      _phoneController.text = draft.phoneNumber ?? '';
      _emailController.text = draft.emailAddress ?? '';
      _smsSelected = draft.smsSelected;
      _whatsappSelected = draft.whatsappSelected;
      _emailSelected = draft.emailSelected;
      _wasEmailProvided = _emailProvided;
      _draftLoaded = true;
    });
    _phoneController.addListener(() {
      setState(() {});
      _saveDraft();
    });
    _emailController.addListener(() {
      // customer.md: the email row "activates once an address is entered" — reactivating
      // `emailSelected` on every empty→non-empty transition is what makes typing a FRESH
      // address after a deselect-and-clear count as activation again, not stay silently
      // deselected (found under review, S5-02, second pass).
      if (!_wasEmailProvided && _emailProvided) {
        _emailSelected = true;
      }
      _wasEmailProvided = _emailProvided;
      setState(() {});
      _saveDraft();
    });
  }

  @override
  void dispose() {
    _phoneController.dispose();
    _emailController.dispose();
    _phoneFocus.dispose();
    _emailFocus.dispose();
    super.dispose();
  }

  Future<void> _saveDraft() async {
    if (_abandoned) return;
    final repository = ref.read(entryRepositoryProvider);
    final current = await repository.loadDraft();
    if (_abandoned) return; // re-checked after the await — see `_abandoned`'s own doc comment
    await repository.saveDraft(
      current.copyWith(
        phoneNumber: _phoneController.text,
        smsSelected: _smsSelected,
        whatsappSelected: _whatsappSelected,
        emailAddress: _emailController.text,
        emailSelected: _emailSelected,
      ),
    );
  }

  void _toggleSms(bool? value) {
    setState(() => _smsSelected = value ?? false);
    _saveDraft();
  }

  void _toggleWhatsapp(bool? value) {
    setState(() => _whatsappSelected = value ?? false);
    _saveDraft();
  }

  void _toggleEmail(bool? value) {
    setState(() => _emailSelected = value ?? false);
    _saveDraft();
  }

  Future<void> _abandon() async {
    _abandoned = true;
    await ref.read(entryRepositoryProvider).abandon();
    if (!mounted) return;
    context.go('/account-entry');
  }

  Future<void> _submit() async {
    final branchCode = _branchCode;
    final accountNumber = _accountNumber;
    if (branchCode == null ||
        accountNumber == null ||
        _bothPhoneChannelsDeselected ||
        !_phoneProvided) {
      return;
    }

    setState(() {
      _submitting = true;
      _errorMessage = null;
    });

    try {
      await ref
          .read(entryRepositoryProvider)
          .submitContactChannels(
            branch: branchCode,
            accountNumber: accountNumber,
            phoneNumber: _phoneController.text.trim(),
            sms: _smsSelected,
            whatsapp: _whatsappSelected,
            emailAddress: (_emailProvided && _emailSelected) ? _emailController.text.trim() : null,
          );
      if (!mounted) return;
      context.go('/channel-verification');
    } on ProfileAlreadyCompleteException {
      // customer.md Stage 0: a "complete" answer clears local state before showing the
      // terminal screen — this path reaches the same terminal outcome mid-journey and must
      // clear it the same way, or the previous customer's data stays prefilled on "back to
      // start" (found under review, S5-02).
      await ref.read(entryRepositoryProvider).abandon();
      if (!mounted) return;
      context.go('/ended');
    } on ContactChannelsRejectedException {
      if (!mounted) return;
      // The backend's 400 carries no distinguishable cause (verified live, S5-02) — an honest
      // generic message, not a guessed specific one. See ContactChannelsRejectedException's doc.
      setState(() => _errorMessage = 'تعذر إتمام الطلب. يرجى التحقق من البيانات المدخلة.');
    } on SessionTemporarilyBlockedException {
      if (!mounted) return;
      setState(() => _errorMessage = 'التحقق متوقف مؤقتًا. يرجى المحاولة لاحقًا.');
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => _errorMessage = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.');
    } catch (_) {
      // An unmapped failure (e.g. a 5xx from a core-banking outage reaching AccountCheckService's
      // sibling on this account) must still leave the customer with something to read, not a
      // silently re-enabled button — found under review, S5-02.
      if (!mounted) return;
      setState(() => _errorMessage = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// The channels that will actually be sent a code.
  List<String> get _selectedChannels => [
    if (_smsSelected) 'sms',
    if (_whatsappSelected) 'whatsapp',
    if (_emailProvided && _emailSelected) 'email',
  ];

  /// Walk comment 4d asked for the old «سيتم حفظ في ملفك: …» sentence to be deleted or replaced
  /// with this line plus the icons of the selected channels. Replaced rather than deleted.
  ///
  /// **The «لن يتم حفظ…» half is deliberately KEPT below this**, and that is a judgement worth
  /// naming: 4d offered deletion, but that clause is the only place the customer is told which
  /// contact details will NOT be stored on their profile, and it was added deliberately under
  /// review at S5-02. Dropping a disclosure is not a presentation change, so it stays until the
  /// product owner says otherwise.
  Widget _verificationTargets(BuildContext context) {
    final selected = _selectedChannels;
    if (selected.isEmpty) {
      return Text(
        'لن يتم حفظ أي وسيلة تواصل في ملفك.',
        style: Theme.of(context).textTheme.bodyMedium,
      );
    }
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 8,
      runSpacing: 4,
      children: [
        Text(
          'سيتم إرسال رموز التحقق لاعتماد:',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        for (final channel in selected) Icon(channelIcon(channel), size: 20),
      ],
    );
  }

  /// Null when every provided channel is selected — there is nothing to disclose.
  String? get _notSavedText {
    final notSaved = <String>[];
    if (!_smsSelected) notSaved.add('الرسائل النصية');
    if (!_whatsappSelected) notSaved.add('واتساب');
    if (_emailProvided && !_emailSelected) notSaved.add('البريد الإلكتروني');
    if (notSaved.isEmpty) return null;
    return 'لن يتم حفظ ${notSaved.join(' و')}.';
  }

  /// An icon beside the label, the same pairing the OTP screen already uses for these channels.
  Widget _channelOption(String wireValue, String label) {
    return Row(
      children: [
        Icon(channelIcon(wireValue), size: 20),
        const SizedBox(width: 8),
        Flexible(child: Text(label)),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    // `_branchCode`/`_accountNumber` null is only possible before `_load()` completes, or (not
    // reachable through this app today) if this screen were somehow opened with no `LocalProgress`
    // row at all — `_submit()` already guards this, but `canSubmit` must too, or Next renders
    // enabled and silently does nothing (found under review, S5-02, second pass).
    final canSubmit =
        !_submitting &&
        // Walk comment 6 asked for the field to be "controlled to be numbers only 10 digits".
        // The formatter caps the TOP; without this, Next was enabled on a single digit and the
        // customer's reward for pressing it was the generic «تعذر إتمام الطلب», which names no
        // cause — the helper text stated a rule the screen did not enforce. Found by
        // `@agent-reviewer`.
        _phoneController.text.trim().length == _localPhoneDigits &&
        !widget.offline &&
        !_bothPhoneChannelsDeselected &&
        _phoneProvided &&
        _branchCode != null &&
        _accountNumber != null;

    return Scaffold(
      appBar: BrandBanner(
        title: const ScreenTitle('اختيار قنوات الاتصال'),
        actions: [
          // Gated on `_draftLoaded`: tappable before `_load()` resolves raced abandonment with
          // the initial pre-fill (see `_load()`'s own doc comment) — found under review, S5-02,
          // second pass.
          if (_draftLoaded)
            TextButton(onPressed: _abandon, child: const Text('التراجع عن الجلسة')),
        ],
      ),
      body: !_draftLoaded
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const JourneyProgress(step: JourneyStep.contactChannels),
                if (widget.offline) const OfflineBanner(),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        // Walk comment 4b: the screen opened straight onto a phone field with no
                        // statement of what it was for.
                        Text(
                          'اختر قنوات الاتصال التي تود اعتمادها في التواصل مع البنك',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                        const SizedBox(height: 16),
                        TextField(
                          controller: _phoneController,
                          focusNode: _phoneFocus,
                          // Walk comment 2 (2026-09-10), on this screen too. The next focusable
                          // after this field is the SMS `CheckboxListTile`, so the stock `next`
                          // action focused a checkbox and closed the keyboard — the exact symptom
                          // the comment reports, on the screen the other half of this commit
                          // edits. Raised by `@agent-reviewer`.
                          //
                          // Not `FieldErrorState`'s chain: this screen does not use that mixin,
                          // and the hop is a single one to the email field.
                          textInputAction: TextInputAction.next,
                          onSubmitted: (_) => _emailFocus.requestFocus(),
                          textDirection: TextDirection.ltr,
                          textAlign: TextAlign.right,
                          keyboardType: TextInputType.phone,
                          inputFormatters: [
                            const ArabicDigitInputFormatter(),
                            FilteringTextInputFormatter.digitsOnly,
                            // Walk comment 6 (2026-09-10): the field accepted any length.
                            //
                            // **Ten is the server's own rule, not a guess.** A Sudan local number
                            // is a trunk `0` plus nine digits — `PhoneNumberNormalizer`'s
                            // `SUDAN_LOCAL_DIGITS_AFTER_ZERO = 9` — which it turns into
                            // `+249XXXXXXXXX`. So ten is exactly what the backend can normalise
                            // from the local form the customer types.
                            //
                            // Stated because the two CAN drift. The form this cap removes is the
                            // `00`-prefixed international one (`normalizeE164`'s `startsWith("00")`
                            // branch), which WAS reachable here — a `+` prefix never was, because
                            // `digitsOnly` above has always stripped it, so naming `+` as the cost
                            // (as the first version of this comment did) named a form that could
                            // not be typed. Corrected after `@agent-reviewer`.
                            //
                            // Refusing `00…` is deliberate: this screen asks a Sudanese retail
                            // customer for their own mobile number in local form, and the hint
                            // below says so. A screen that must accept a foreign number needs its
                            // own field, not a wider cap here.
                            LengthLimitingTextInputFormatter(_localPhoneDigits),
                          ],
                          decoration: InputDecoration(
                            labelText: 'رقم الهاتف',
                            // The example is Latin digits inside an Arabic sentence sharing one
                            // `Text`, so there is no paragraph boundary to isolate it — FSI/PDI
                            // is the right tool here and `LtrValue` is not (see `ltr_value.dart`,
                            // which spells out why the two are not interchangeable).
                            helperText:
                                'عشرة خانات (مثال: ${LtrValue.isolate('0912******')})',
                          ),
                        ),
                        const SizedBox(height: 24),
                        Material(
                          // Group A: this box is hand-rolled rather than a `Card`, so `cardTheme`
                          // cannot reach it — without an explicit fill it would stay flat while
                          // every field around it lifts off the tinted canvas.
                          //
                          // `Material`, NOT a `Container` carrying a `BoxDecoration` colour. The rows
                          // below are `CheckboxListTile`s, and a ListTile paints its background and
                          // its ink splashes onto the nearest Material ANCESTOR — so a filled
                          // DecoratedBox in between hides every tap ripple in this group. Flutter
                          // asserts on exactly that, which is how the suite caught the first attempt.
                          color: Theme.of(context).colorScheme.surfaceContainerLowest,
                          shape: RoundedRectangleBorder(
                            side: BorderSide(
                              color: _bothPhoneChannelsDeselected
                                  ? Theme.of(context).colorScheme.error
                                  : Theme.of(context).colorScheme.outline,
                            ),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(12),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  _bothPhoneChannelsDeselected
                                      ? 'يجب اختيار وسيلة واحدة على الأقل لاستلام الرمز'
                                      : 'اختر وسيلة واحدة على الأقل لاستلام الرمز',
                                  style: _bothPhoneChannelsDeselected
                                      ? TextStyle(color: Theme.of(context).colorScheme.error)
                                      : Theme.of(context).textTheme.titleSmall,
                                ),
                                CheckboxListTile(
                                  value: _smsSelected,
                                  onChanged: _toggleSms,
                                  title: _channelOption('sms', 'الرسائل النصية'),
                                  controlAffinity: ListTileControlAffinity.leading,
                                ),
                                CheckboxListTile(
                                  value: _whatsappSelected,
                                  onChanged: _toggleWhatsapp,
                                  title: _channelOption('whatsapp', 'واتساب'),
                                  controlAffinity: ListTileControlAffinity.leading,
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        TextField(
                          controller: _emailController,
                          focusNode: _emailFocus,
                          textInputAction: TextInputAction.done,
                          onSubmitted: (_) => FocusScope.of(context).unfocus(),
                          keyboardType: TextInputType.emailAddress,
                          decoration: const InputDecoration(
                            labelText: 'البريد الإلكتروني (اختياري)',
                          ),
                        ),
                        // customer.md: "The email channel row activates once an address is
                        // entered, and is deselectable once active."
                        if (_emailProvided)
                          CheckboxListTile(
                            value: _emailSelected,
                            onChanged: _toggleEmail,
                            title: _channelOption('email', 'حفظ البريد الإلكتروني كوسيلة تواصل'),
                            controlAffinity: ListTileControlAffinity.leading,
                          ),
                        const SizedBox(height: 16),
                        _verificationTargets(context),
                        if (_notSavedText != null) ...[
                          const SizedBox(height: 8),
                          Text(
                            _notSavedText!,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                        if (_errorMessage != null) ...[
                          const SizedBox(height: 12),
                          Text(
                            _errorMessage!,
                            style: TextStyle(color: Theme.of(context).colorScheme.error),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                StageActionBar.previousNext(
                  onNext: canSubmit ? _submit : null,
                  busy: _submitting,
                ),
              ],
            ),
    );
  }
}
