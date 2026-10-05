import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/dataentry/data_entry_providers.dart';
import '../../core/entry/channel_labels.dart';
import '../../core/widgets/screen_title.dart';
import '../../core/entry/entry_models.dart';
import '../../core/entry/entry_providers.dart';
import '../../core/text/arabic_digit_input_formatter.dart';
import '../../core/text/arabic_noun_agreement.dart';
import 'offline_banner.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 2 — channel verification (docs/journeys/customer.md). Reached either fresh from
/// Stage 1b's successful submission or resumed by `LaunchScreen` for a profile whose OTPs were
/// already sent but not yet verified.
///
/// **Email correction (BL-101, built 2026-09-10).** The email row carries an edit-in-place
/// control implementing customer.md Stage 2 "Corrections". `POST /api/v1/otp/resend` accepts an
/// optional `correctedEmailAddress` — it has since S4-06, and BL-012 has been CLOSED since then;
/// an earlier revision of this comment asserted the opposite for five sessions, which is what let
/// a live journey rule go unimplemented. The backend applies the correction inside the same
/// transaction that decides the reservation, so **the address changes even when the resend is
/// refused** and the old address's code dies with it. Two consequences this screen must honour:
/// the success copy has to distinguish "corrected and sent" from "corrected, nothing sent yet",
/// and the control is withheld once resends are exhausted or the channel is locked, because a
/// correction there would destroy the last usable code with no way to obtain another (BL-098).
///
/// **Disclosed limitations (S5-04, and one from S8-10), not built here because each needs a
/// backend change out of the relevant session's scope:**
/// - **The BL-098 guard does not survive a process restart.** `resendExhausted` and `locked` are
///   per-screen-session flags: `pendingVerificationChannels()` carries only channel/state/mask, so
///   after a kill-and-resume a genuinely resend-capped email row is offered the correction control
///   again. Saving there applies the correction and invalidates the last usable code, which is the
///   outcome BL-098 exists to prevent. Unlike the same staleness on the resend and verify buttons —
///   self-correcting, one wasted round trip — this one is destructive, so it is stated rather than
///   filed as harmless. Closing it needs the backend to report per-channel resend state on load;
///   the client must not infer it (CLAUDE.md: policy values are backend-owned). Found by
///   `@agent-reviewer`, S8-10; tracked as BL-103.
/// - A channel *locked* mid-session, or mid-resend-countdown, and then backgrounded shows as
///   plain "unverified" again on resume — only `verified`/`alreadyVerified` outcomes are
///   write-through cached (`EntryRepository.verifyChannel`/`resendChannel`). Self-correcting: the
///   very next verify/resend attempt re-learns the true state from the backend, at the cost of
///   one redundant round trip — including a channel already locked from an earlier verify
///   sequence, which a resend attempt in this state now learns and reacts to identically (see
///   `_resend`'s own `_allPhoneRowsLocked` check, added under review, S5-04).
/// - The resend control does not proactively show a 30s/60s/120s lock the instant a code is
///   issued — `OtpResendResponse.secondsUntilAllowed` is only ever meaningful on `TOO_SOON`
///   (`-1` on `ISSUED`), so the button stays tappable until a premature tap is told the real
///   countdown. Deliberate: CLAUDE.md forbids re-enforcing policy values client-side as if the
///   client were authoritative, and the backend does not expose "next allowed at" on success.
///   Found under review, S5-04.
class ChannelVerificationScreen extends ConsumerStatefulWidget {
  const ChannelVerificationScreen({super.key, this.offline = false});

  /// Whether this resume could not be confirmed with the backend (customer.md Stage 0).
  final bool offline;

  @override
  ConsumerState<ChannelVerificationScreen> createState() => _ChannelVerificationScreenState();
}

/// The outcome of an email correction, as the row needs to report it (BL-101).
///
/// [correctedNotSent] exists because the backend applies a correction even when it refuses the
/// resend, so "the address changed" and "a code is on its way" are independent facts and the copy
/// must not conflate them.
enum _CorrectionNotice { none, correctedAndSent, correctedNotSent, correctedNoneAvailable }

/// One row's mutable state. A plain class, not a drift/riverpod model — deliberately ephemeral
/// (per-screen-session only), mirroring `ContactChannelsScreen`'s own `setState`-based, no-
/// `StateNotifier` convention.
class _ChannelRowState {
  _ChannelRowState({required this.channel, required this.maskedDestination, required this.state});

  final String channel;

  /// Mutable since BL-101: a corrected email address comes back from the resend response with a
  /// new mask, which the row must show instead of the typo the customer just fixed.
  String maskedDestination;
  ChannelState state;

  /// Email row only. True while the in-place correction editor is open.
  bool editing = false;

  /// Email row only, created on first edit and disposed with [codeController].
  TextEditingController? emailController;

  /// The outcome of the last email correction, reported in a neutral (non-error) line under the
  /// row. Held as a state value rather than a rendered string so the "no code sent yet" case can
  /// re-render its countdown as the ticker runs it down — a frozen string would still be claiming
  /// "resend in 28 s" a minute later.
  _CorrectionNotice notice = _CorrectionNotice.none;

  /// Set on `OtpVerifyOutcome.channelLocked`/`OtpResendOutcome.channelLocked`. Not persisted (see
  /// the screen's own doc comment) — re-learned from the backend on the next attempt if lost.
  bool locked = false;

  final codeController = TextEditingController();
  bool submitting = false;
  bool resendSubmitting = false;
  String? errorText;

  /// Only ever set from `OtpResendResult.secondsUntilAllowed` — never a client-guessed schedule
  /// (CLAUDE.md: policy values are backend-owned, the UI only ever reflects what it is told).
  int? resendSecondsRemaining;
  bool resendExhausted = false;

  bool get isPhone => channel == 'sms' || channel == 'whatsapp';
}

class _ChannelVerificationScreenState extends ConsumerState<ChannelVerificationScreen> {
  Map<String, _ChannelRowState>? _rows;
  bool _loaded = false;
  Timer? _ticker;

  /// True while `_onNext` is running `DataEntryRepository.prepareCatalog()` — the real Stage 2→3
  /// boundary (S5-05). Distinct from any per-row `submitting` flag; this blocks the whole screen's
  /// Next button, not one channel's verify/resend action.
  bool _preparingDataEntry = false;

  /// Set when `prepareCatalog()` reports no usable cache for one of stages 3-6's five lists —
  /// client rule 5's "block stage 3 if there is no prior cache." The customer stays on this
  /// screen and can retry once connectivity returns.
  String? _catalogError;

  /// Mirrors `ContactChannelsScreen._abandoned` exactly — same race, same fix (S5-02, second
  /// review pass): abandon-while-loading must not let a late-arriving `_load()` write anything.
  bool _abandoned = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final repository = ref.read(entryRepositoryProvider);
    final channels = await repository.pendingVerificationChannels();
    if (!mounted || _abandoned) return;
    setState(() {
      _rows = {
        for (final c in channels)
          c.channel: _ChannelRowState(
            channel: c.channel,
            maskedDestination: c.maskedDestination,
            state: c.state,
          ),
      };
      _loaded = true;
    });
    _ticker = Timer.periodic(const Duration(seconds: 1), _tick);
  }

  void _tick(Timer timer) {
    if (!mounted) {
      timer.cancel();
      return;
    }
    var any = false;
    for (final row in _rows!.values) {
      final remaining = row.resendSecondsRemaining;
      if (remaining != null && remaining > 0) {
        row.resendSecondsRemaining = remaining - 1;
        any = true;
      }
    }
    if (any) setState(() {});
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _rows?.values.forEach((row) {
      row.codeController.dispose();
      row.emailController?.dispose();
    });
    super.dispose();
  }

  Future<void> _abandon() async {
    _abandoned = true;
    await ref.read(entryRepositoryProvider).abandon();
    if (!mounted) return;
    context.go('/account-entry');
  }

  Future<void> _verify(_ChannelRowState row) async {
    final code = row.codeController.text.trim();
    if (code.length != 6 || row.submitting || row.locked) return;

    setState(() {
      row.submitting = true;
      row.errorText = null;
    });

    DateTime? sessionBlockedUntil;
    try {
      final result = await ref
          .read(entryRepositoryProvider)
          .verifyChannel(channel: row.channel, code: code);
      if (!mounted) return;
      setState(() {
        switch (result.outcome) {
          case OtpVerifyOutcome.verified:
            row.state = ChannelState.verified;
            row.codeController.clear();
            // The correction notice described a code that has now been used; leaving it would
            // print "a new code was sent" beside "موثقة".
            row.notice = _CorrectionNotice.none;
          case OtpVerifyOutcome.wrongCode:
            row.errorText = 'الرمز غير صحيح. حاول مرة أخرى.';
          case OtpVerifyOutcome.expired:
            row.errorText = 'انتهت صلاحية الرمز. اطلب رمزاً جديداً عبر إعادة الإرسال.';
          case OtpVerifyOutcome.channelLocked:
            row.locked = true;
            row.codeController.clear();
        }
      });
      sessionBlockedUntil = result.sessionBlockedUntil;
    } on UnknownOtpChannelException {
      if (!mounted) return;
      setState(() => row.errorText = 'تعذر إتمام الطلب.');
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => row.errorText = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.');
    } catch (_) {
      if (!mounted) return;
      setState(() => row.errorText = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => row.submitting = false);
    }

    if (sessionBlockedUntil != null) _goToBlockedTerminal(sessionBlockedUntil);
  }

  /// Whether the email row may offer its in-place correction control (BL-098).
  ///
  /// This is `_buildRow`'s own `canResend` minus the countdown term: a correction during a
  /// `TOO_SOON` countdown is legitimate and stays available (the customer simply waits for the
  /// code), but a correction once resends are exhausted, or on a locked or verified channel, would
  /// invalidate the last usable code with no route to another and strand the channel unverifiable
  /// for the session. `widget.offline` is included for the same reason it gates the other two
  /// actions — the correction is a network call, not a local edit.
  bool _canCorrectEmail(_ChannelRowState row) =>
      row.channel == 'email' &&
      !widget.offline &&
      row.state != ChannelState.verified &&
      !row.locked &&
      !row.resendExhausted &&
      !row.resendSubmitting;

  Future<void> _beginEmailEdit(_ChannelRowState row) async {
    // The row itself only ever holds the MASK; the full address lives in the Stage 1b draft, which
    // survives that stage's submit (`submitContactChannels` writes only `LocalProgress`).
    //
    // A failed draft read must not become an uncaught async error — every other action on this
    // screen has a catch arm, and this one had none (found by `@agent-reviewer`, S8-10). An empty
    // prefill is a fully usable editor, so the failure degrades to "type it again" rather than
    // blocking the repair.
    String? prefill;
    try {
      prefill = (await ref.read(entryRepositoryProvider).loadDraft()).emailAddress;
    } catch (_) {
      prefill = null;
    }
    if (!mounted) return;
    setState(() {
      row.emailController ??= TextEditingController();
      row.emailController!.text = prefill ?? '';
      row.editing = true;
      row.errorText = null;
      row.notice = _CorrectionNotice.none;
    });
  }

  void _cancelEmailEdit(_ChannelRowState row) {
    setState(() {
      row.editing = false;
      row.errorText = null;
    });
  }

  /// Saves a corrected email address and resends in one call — the backend applies the correction
  /// inside the reservation transaction, so there is no separate "save" endpoint to hit first.
  ///
  /// Validation mirrors the backend's own boundary (`OtpVerificationController`: non-blank, at
  /// most 254 characters per RFC 5321 §4.5.3.1.3) and stops there. **No format check**, matching
  /// both the endpoint and Stage 1b's own entry field — inventing one here would reject addresses
  /// the rest of the system accepts.
  Future<void> _saveCorrectedEmail(_ChannelRowState row) async {
    final controller = row.emailController;
    if (controller == null || row.resendSubmitting) return;
    final corrected = controller.text.trim();

    if (corrected.isEmpty) {
      setState(() => row.errorText = 'أدخل البريد الإلكتروني.');
      return;
    }
    if (corrected.length > 254) {
      setState(() => row.errorText = 'البريد الإلكتروني طويل جداً.');
      return;
    }
    // `_resend` would drop this silently — no state change, no error, no notice. Say why instead.
    if (row.locked || row.resendExhausted) {
      setState(() {
        row.editing = false;
        row.errorText = 'تعذر تعديل البريد الإلكتروني الآن.';
      });
      return;
    }

    await _resend(row, correctedEmailAddress: corrected);
  }

  Future<void> _resend(_ChannelRowState row, {String? correctedEmailAddress}) async {
    if (row.resendSubmitting || row.locked || row.resendExhausted) return;

    setState(() {
      row.resendSubmitting = true;
      row.errorText = null;
      row.notice = _CorrectionNotice.none;
    });

    try {
      final result = await ref
          .read(entryRepositoryProvider)
          .resendChannel(channel: row.channel, correctedEmailAddress: correctedEmailAddress);
      if (!mounted) return;
      setState(() {
        switch (result.outcome) {
          case OtpResendOutcome.issued:
            row.resendSecondsRemaining = null;
          case OtpResendOutcome.tooSoon:
            row.resendSecondsRemaining = result.secondsUntilAllowed ?? 0;
          case OtpResendOutcome.capExhausted:
            row.resendExhausted = true;
            // The editor must not survive a state that makes correcting destructive — otherwise
            // it stays on screen with an enabled-looking save button that `_resend`'s own guard
            // silently drops. Found by `@agent-reviewer`, S8-10.
            row.editing = false;
          case OtpResendOutcome.channelLocked:
            row.locked = true;
            row.codeController.clear();
            row.editing = false;
          case OtpResendOutcome.alreadyVerified:
            row.state = ChannelState.verified;
            row.locked = false;
        }

        if (correctedEmailAddress != null) {
          row.editing = false;
          // `OtpVerificationService.reserveResend` SKIPS the correction when the channel is
          // already VERIFIED — the address was not changed, so neither the mask nor the notice may
          // claim it was. Every other outcome did apply it, refusal included.
          if (result.outcome != OtpResendOutcome.alreadyVerified) {
            row.maskedDestination = result.maskedDestination;
            // The old address's challenge was invalidated inside the same transaction, so whatever
            // is sitting in the code box is now dead. Clearing it stops the customer spending a
            // wrong-code attempt on a code that can no longer match.
            row.codeController.clear();
            row.notice = switch (result.outcome) {
              OtpResendOutcome.issued => _CorrectionNotice.correctedAndSent,
              // No further code can be obtained for this channel at all, so "not sent yet" would
              // be a promise. Said as an end state instead.
              OtpResendOutcome.capExhausted ||
              OtpResendOutcome.channelLocked => _CorrectionNotice.correctedNoneAvailable,
              // Named rather than `_`: a wildcard here would silently render "not sent YET" for
              // any future terminal outcome, which is the exact promise this enum exists to stop.
              // `alreadyVerified` is unreachable — excluded by the enclosing `if`.
              OtpResendOutcome.tooSoon ||
              OtpResendOutcome.alreadyVerified => _CorrectionNotice.correctedNotSent,
            };
          }
        }
      });
      // A resend never LOCKS a channel itself — `OtpVerificationService.reserveResend` only ever
      // returns `CHANNEL_LOCKED` when the channel was already locked by an earlier wrong-code
      // sequence. The reachable case this guards is the resume-staleness gap documented on this
      // class: a channel locked before the app was last backgrounded shows as plain "unverified"
      // here (lock state isn't persisted), so a customer might tap resend rather than verify on
      // it — and `OtpResendResponse` carries no session-block timestamp (only `/otp/verify`
      // does), so without this check learning the lock via resend on the SECOND phone channel
      // would leave both rows locked with no navigation to the terminal screen at all. The exact
      // unlock time isn't known here, so the message omits it rather than guessing. Found under
      // review, S5-04 (both the gap and this comment's earlier, inaccurate "resend causes the
      // lock" framing, corrected second pass).
      if (result.outcome == OtpResendOutcome.channelLocked && _allPhoneRowsLocked) {
        _goToBlockedTerminal(null);
      }
    } on UnknownOtpChannelException {
      if (!mounted) return;
      setState(() => row.errorText = 'تعذر إتمام الطلب.');
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => row.errorText = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.');
    } catch (_) {
      if (!mounted) return;
      setState(() => row.errorText = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => row.resendSubmitting = false);
    }
  }

  /// `sessionBlockedUntilIso` non-null means every selected phone channel has locked
  /// (S3-08/R-044) — customer.md: "the customer is told to try again later or visit a branch."
  /// Local state is deliberately left intact (see the session's own design notes) so a relaunch
  /// after the block naturally resumes back into this screen. [blockedUntil] is `null` when the
  /// block was inferred locally from `_allPhoneRowsLocked` (a `resendChannel` call, whose response
  /// carries no timestamp) rather than told to us directly by `/otp/verify`.
  void _goToBlockedTerminal(DateTime? blockedUntil) {
    // Rounds UP, not down — found under review, S5-04: truncating via `.inMinutes` on a
    // server-computed "now + 15 minutes" reads as 14 the instant any network latency elapses,
    // telling the customer to retry a minute before the block actually lifts.
    final secondsLeft = blockedUntil?.difference(DateTime.now()).inSeconds ?? 0;
    final minutesLeft = secondsLeft > 0 ? (secondsLeft + 59) ~/ 60 : 0;
    // Arabic numeral–noun agreement via the shared helper (S5-05 generalisation of this screen's
    // own S5-04 fix — `OtpVerificationService.finish` reports the REMAINING lock time on every
    // verify attempt made while blocked, so any minute count from 1 up to the escalated 60 is
    // reachable here, not just the moment the lock is first set).
    final message = minutesLeft > 0
        ? 'تعذر التحقق من أي قناة هاتف. يرجى المحاولة مرة أخرى بعد '
              '${ArabicNounAgreement.minutes.phrase(minutesLeft)}، أو زيارة أقرب فرع.'
        : 'تعذر التحقق من أي قناة هاتف. يرجى المحاولة مرة أخرى لاحقًا، أو زيارة أقرب فرع.';
    context.go('/terminal', extra: message);
  }

  bool get _allPhoneRowsLocked => _rowsList.where((r) => r.isPhone).every((r) => r.locked);

  List<_ChannelRowState> get _rowsList => _rows!.values.toList();

  bool get _anyPhoneVerified =>
      _rowsList.any((r) => r.isPhone && r.state == ChannelState.verified);

  /// Deliberately invariant phrasing ("باقي القنوات", "لن يتم حفظ") — found under review, S5-04:
  /// an earlier version used dual-form verbs (`منهما`/`تُحفظا`) that are only grammatically correct
  /// when exactly two channels are unverified, and silently wrong the moment a third selected
  /// channel makes that a one- or three-item list. `ContactChannelsScreen._consequenceText`
  /// already establishes this invariant style for the same reason.
  String get _statusLine {
    final verified = _rowsList
        .where((r) => r.state == ChannelState.verified)
        .map((r) => channelLabel(r.channel))
        .toList();
    final notVerified = _rowsList
        .where((r) => r.state != ChannelState.verified)
        .map((r) => channelLabel(r.channel))
        .toList();
    if (verified.isEmpty) return 'لم يتم التحقق من أي قناة بعد.';
    final verifiedText = 'تم التحقق من: ${verified.join('، ')}.';
    if (notVerified.isEmpty) return verifiedText;
    return '$verifiedText لن يتم حفظ باقي القنوات (${notVerified.join('، ')}) في ملفك حتى يتم '
        'التحقق منها.';
  }

  Future<void> _onNext() async {
    if (!_anyPhoneVerified || _preparingDataEntry) return;
    final unverified = _rowsList.where((r) => r.state != ChannelState.verified).toList();
    if (unverified.isNotEmpty) {
      final proceed = await _confirmContinue(unverified);
      if (proceed != true) return;
    }

    // The real Stage 2→3 boundary (reference-data.md client rule 3, S5-05): sync/activate/pin the
    // five lists stages 3-6 need BEFORE advancing past this screen, so a customer who somehow has
    // no usable cache is blocked here — with a retry available — rather than landing on a broken
    // Stage 3 with no pickers to show (client rule 5: "block stage 3 if there is no prior cache").
    setState(() {
      _preparingDataEntry = true;
      _catalogError = null;
    });
    final ready = await ref.read(dataEntryRepositoryProvider).prepareCatalog();
    if (!mounted) return;
    if (!ready) {
      setState(() {
        _preparingDataEntry = false;
        _catalogError = 'تعذر تحميل بيانات القوائم المطلوبة. يرجى التحقق من الاتصال بالإنترنت '
            'والمحاولة مرة أخرى.';
      });
      return;
    }

    await ref.read(entryRepositoryProvider).completeVerification();
    if (!mounted) return;
    context.go('/stage-3');
  }

  /// customer.md Stage 2 "Proceeding": named specifics, "go back and verify" reads first so
  /// continuing is a deliberate act. Never shown when every selected channel is already verified.
  Future<bool?> _confirmContinue(List<_ChannelRowState> unverified) {
    final unverifiedNames = unverified.map((r) => channelLabel(r.channel)).join(' و');
    final verifiedNames = _rowsList
        .where((r) => r.state == ChannelState.verified)
        .map((r) => channelLabel(r.channel))
        .join(' و');
    return showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        // "لم يتم التحقق من: ..." — no countable noun to agree with the list's length, avoiding
        // the same class of bug the plural "القنوات التالية" phrasing had (found under review,
        // S5-04: wrong for a single unverified channel, matching `_statusLine`'s own fix above).
        title: Text('لم يتم التحقق من: $unverifiedNames'),
        content: Text(
          'سيتم حفظ $verifiedNames فقط في ملفك. لن يتمكن البنك من التواصل معك عبر القنوات غير '
          'الموثقة، ولا يمكن إضافة قنوات جديدة لاحقًا.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('العودة والتحقق'),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            // "ب" attaches directly, no tatweel connector — found under review, S5-04: the
            // earlier "بـ$verifiedNames" rendered as "بـالرسائل النصية" for any name starting
            // with the definite article, where standard orthography merges to "بالرسائل".
            child: Text('المتابعة ب$verifiedNames فقط'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(
        title: const ScreenTitle('التحقق من قنوات الاتصال'),
        actions: [
          if (_loaded) TextButton(onPressed: _abandon, child: const Text('التراجع عن الجلسة')),
        ],
      ),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const JourneyProgress(step: JourneyStep.channelVerification),
                if (widget.offline) const OfflineBanner(),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (final row in _rowsList) _buildRow(row),
                        // W-10 / walk comment 5d, a product-owner decision taken 2026-09-07 and
                        // recorded on BL-091: once ANY phone channel has proved it can receive a
                        // code, that number is settled and the correction path closes.
                        //
                        // **This supersedes a written journey rule.** customer.md Stage 2
                        // "Corrections" lists «Phone number wrong → Back to 1b» as an available
                        // repair; it is available only until a phone channel verifies. The journey
                        // doc is amended in the same commit — this is a recorded reversal, not a
                        // silent one.
                        if (!_anyPhoneVerified)
                          TextButton(
                            onPressed: () => context.go('/contact-channels'),
                            child: const Text('رقم الهاتف غير صحيح؟ العودة لتعديله'),
                          ),
                      ],
                    ),
                  ),
                ),
                StageActionBar(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        _statusLine,
                        style: Theme.of(context).textTheme.bodyMedium,
                        textAlign: TextAlign.center,
                      ),
                      if (_catalogError != null) ...[
                        const SizedBox(height: 8),
                        Text(
                          _catalogError!,
                          style: TextStyle(color: Theme.of(context).colorScheme.error),
                          textAlign: TextAlign.center,
                        ),
                      ],
                      const SizedBox(height: 8),
                      FilledButton(
                        onPressed: (_anyPhoneVerified && !_preparingDataEntry) ? _onNext : null,
                        child: _preparingDataEntry
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : const Text('التالي'),
                      ),
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  Widget _buildRow(_ChannelRowState row) {
    final verified = row.state == ChannelState.verified;
    final canVerify =
        !widget.offline &&
        !verified &&
        !row.locked &&
        !row.submitting &&
        row.codeController.text.trim().length == 6;
    final resendCountingDown = (row.resendSecondsRemaining ?? 0) > 0;
    final canResend =
        !widget.offline &&
        !verified &&
        !row.locked &&
        !row.resendSubmitting &&
        !row.resendExhausted &&
        !resendCountingDown;

    return Container(
      key: ValueKey('row_${row.channel}'),
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        // Group A: same reason as the Stage 1b group box — a hand-rolled container needs its fill
        // named explicitly, or it reads flat against the new tinted canvas.
        color: Theme.of(context).colorScheme.surfaceContainerLowest,
        border: Border.all(
          color: row.locked
              ? Theme.of(context).colorScheme.error
              : Theme.of(context).colorScheme.outline,
        ),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(channelIcon(row.channel)),
              const SizedBox(width: 8),
              Text(channelLabel(row.channel), style: Theme.of(context).textTheme.titleSmall),
              const Spacer(),
              Text(row.maskedDestination),
            ],
          ),
          if (!verified) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    key: ValueKey('code_${row.channel}'),
                    controller: row.codeController,
                    // D5.3: `done`, NEVER `next`. These are three INDEPENDENT codes, one per
                    // channel, each with its own verify button — a "next" between the rows
                    // invites entering the WhatsApp code in the SMS row, which would spend a
                    // wrong-code attempt on a code the customer actually received correctly.
                    textInputAction: TextInputAction.done,
                    enabled: !row.locked,
                    textDirection: TextDirection.ltr,
                    textAlign: TextAlign.right,
                    keyboardType: TextInputType.number,
                    maxLength: 6,
                    inputFormatters: [
                      const ArabicDigitInputFormatter(),
                      FilteringTextInputFormatter.digitsOnly,
                    ],
                    decoration: const InputDecoration(labelText: 'رمز التحقق', counterText: ''),
                    // D5.4: dismiss the keyboard at the sixth digit, which is what reveals the
                    // verify button — it enables at exactly six and is otherwise covered by the
                    // keyboard on a 5-inch screen.
                    //
                    // NO AUTO-SUBMIT, deliberately (PO-approved): a mid-paste or mid-correction
                    // transient would momentarily read six digits and spend one of a strictly
                    // limited number of wrong-code attempts. That cost far exceeds one extra tap.
                    onChanged: (value) {
                      if (value.length == 6) FocusScope.of(context).unfocus();
                      setState(() {});
                    },
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: canVerify ? () => _verify(row) : null,
                  child: row.submitting
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('تحقق'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            TextButton(
              // Keyed since BL-101: the email row now holds a second `TextButton` (the correction
              // affordance), so a by-type descendant finder is no longer unambiguous there.
              key: ValueKey('resend_${row.channel}'),
              onPressed: canResend ? () => _resend(row) : null,
              child: Text(_resendLabel(row)),
            ),
            // BL-101 — customer.md Stage 2 "Corrections": "Email address mistyped → editable in
            // place on its row, then resend." Email row only; the phone number's repair is the
            // separate link at the foot of the screen, because changing it is a Stage 1b re-entry
            // that unverifies every channel rather than a field on this call.
            if (row.channel == 'email' && !row.editing && _canCorrectEmail(row))
              TextButton(
                key: const ValueKey('correct_email_start'),
                onPressed: () => _beginEmailEdit(row),
                child: const Text('البريد الإلكتروني غير صحيح؟ تعديله'),
              ),
            // `row.editing` alone is not enough: the customer can tap resend or verify with the
            // editor open, and a cap or lock arriving then makes saving destructive. Kept visible
            // during an in-flight save (`resendSubmitting`), which is why `_canCorrectEmail` is
            // not reused verbatim here.
            if (row.editing && !row.locked && !row.resendExhausted && !verified)
              _buildEmailEditor(row),
            if (row.errorText != null)
              Text(row.errorText!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          if (row.notice != _CorrectionNotice.none) ...[
            const SizedBox(height: 4),
            Text(
              _noticeText(row),
              key: ValueKey('notice_${row.channel}'),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
          const SizedBox(height: 4),
          Text(
            // Feminine forms — "القناة" (channel) is feminine, matching "هذه القناة" in the
            // locked message below (found under review, S5-04: the earlier masculine
            // "موثق"/"حفظه" disagreed with it).
            verified ? 'موثقة — سيتم حفظها في ملفك' : 'غير موثقة — لن يتم حفظها في ملفك',
            style: TextStyle(
              color: verified
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.error,
            ),
          ),
          if (row.locked)
            Text(
              'تم قفل هذه القناة بعد محاولات خاطئة متكررة، ولا يمكن إعادة المحاولة.',
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
        ],
      ),
    );
  }

  /// The in-place email correction editor (BL-101), rendered inside the email row.
  ///
  /// Layout follows the mockup the product owner approved on 2026-09-10: the save action is
  /// **full width on its own line**, never sharing one with Cancel. At 15 dp the label does not fit
  /// beside a second control on a 360 dp screen, and a truncated action label is a worse failure
  /// than a two-line one — so the button wraps rather than clipping.
  Widget _buildEmailEditor(_ChannelRowState row) {
    // While a countdown is running the backend will certainly refuse, so the action says so rather
    // than promising a code; see `_noticeText` for the message that follows it.
    //
    // This is NOT a total invariant, and the narrower claim is the honest one (found by
    // `@agent-reviewer`, S8-10): immediately after an ISSUED resend the client holds no countdown
    // at all — `secondsUntilAllowed` is `-1` on success, and CLAUDE.md forbids synthesising the
    // 30s/60s/120s schedule client-side — so the label reads "and resend" while the backend would
    // in fact answer TOO_SOON. Bounded, because the notice that follows the call still reports
    // truthfully that nothing was sent. Same root cause as the resend button re-enabling
    // immediately after ISSUED, which is the third disclosed limitation on this class.
    final countingDown = (row.resendSecondsRemaining ?? 0) > 0;
    final saveLabel = countingDown
        ? 'حفظ البريد الجديد فقط'
        : 'حفظ البريد الجديد وإعادة إرسال الرمز';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        TextField(
          key: const ValueKey('corrected_email'),
          controller: row.emailController,
          enabled: !row.resendSubmitting,
          textInputAction: TextInputAction.done,
          keyboardType: TextInputType.emailAddress,
          // Matches Stage 1b's own email field exactly, which sets no explicit `textDirection` —
          // whatever that renders as today is the precedent to match, not to diverge from in a
          // change about something else.
          decoration: const InputDecoration(labelText: 'البريد الإلكتروني'),
          // D5.4's reasoning, applied here: dismissing the keyboard on submit is what reveals the
          // save button, which sits below the field on a 5-inch screen.
          onSubmitted: (_) => FocusScope.of(context).unfocus(),
        ),
        const SizedBox(height: 10),
        FilledButton(
          key: const ValueKey('corrected_email_save'),
          // `widget.offline` gates the ACTION, not the editor's render condition — removing the
          // editor on a connectivity blip would discard what the customer typed.
          onPressed: (row.resendSubmitting || widget.offline)
              ? null
              : () => _saveCorrectedEmail(row),
          child: row.resendSubmitting
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : Text(saveLabel, textAlign: TextAlign.center),
        ),
        TextButton(
          key: const ValueKey('corrected_email_cancel'),
          onPressed: row.resendSubmitting ? null : () => _cancelEmailEdit(row),
          child: const Text('إلغاء'),
        ),
        // Stated BEFORE the tap, not after it. The backend invalidates the old address's challenge
        // in the same transaction as the correction, so a customer holding a working code for the
        // old address needs to know that saving destroys it.
        Text(
          'سيتم إلغاء الرمز المرسل إلى العنوان السابق.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }

  /// A seconds count for display, e.g. `30 ث` — ASCII digits, as the rest of this screen renders
  /// them (the Arabic-Indic form appears only in customer-typed input, which
  /// `ArabicDigitInputFormatter` folds to ASCII at the field).
  ///
  /// **Deliberately the abbreviation «ث», and deliberately NOT routed through
  /// [ArabicNounAgreement]** — the question was raised because interpolating a count into Arabic is
  /// the failure class that produced four separate defects at S5-04, so the reasoning is recorded
  /// rather than left implicit.
  ///
  /// «ث» is a unit SYMBOL, like "s" or "min" in English. Arabic numeral–noun agreement (tamyīz)
  /// governs spelled-out counted nouns — which is exactly what [ArabicNounAgreement] exists to
  /// handle, varying the noun across n == 1, n == 2, 3–10 and 11+. Unit abbreviations are not
  /// counted nouns and are written invariantly at every n, so this string is genuinely invariant
  /// across all four bands rather than accidentally readable at some of them. Asserted at those
  /// four boundaries by `channel_verification_screen_test.dart`.
  ///
  /// Spelling it out would also be **worse than the abbreviation, not merely more verbose.** Both
  /// call sites put the count after «بعد», a preposition governing the genitive, and
  /// [ArabicNounAgreement] carries a single `dual` form — the nominative — so a `seconds` entry
  /// would render «بعد ثانيتان» at n == 2 where the genitive «ثانيتين» is required. The helper's
  /// shape cannot express that case distinction, so routing through it would introduce the very
  /// class of error it is meant to prevent. (The existing `minutes` caller in
  /// [_goToBlockedTerminal] has that defect live at n == 2 — filed separately, not fixed here,
  /// since it is PO-gated customer copy outside this change.)
  static String _secondsAr(int seconds) => '$seconds ث';

  /// Rendered at BUILD time, not stored when the response arrives, so the countdown inside it
  /// stays true as the ticker runs down — a string frozen at the moment of the refusal would still
  /// be promising "إعادة الإرسال بعد 28 ث" long after the resend became available. Found by this
  /// session's own test run, which drove the ticker to zero and caught the stale text.
  String _noticeText(_ChannelRowState row) {
    if (row.notice == _CorrectionNotice.correctedAndSent) {
      return 'تم تحديث البريد الإلكتروني وإرسال رمز جديد.';
    }
    if (row.notice == _CorrectionNotice.correctedNoneAvailable) {
      // An end state, not a wait: no further code can be obtained for this channel in this
      // session, so saying "not sent YET" would promise one that is never coming.
      return 'تم تحديث البريد الإلكتروني، ولا يمكن إرسال رمز جديد لهذه القناة في هذه الجلسة.';
    }
    // correctedNotSent — the address changed but the resend was refused. Said plainly, because the
    // customer has just been shown a NEW masked address and would otherwise reasonably assume a
    // code is already on its way to it.
    final remaining = row.resendSecondsRemaining;
    if (remaining != null && remaining > 0) {
      return 'تم تحديث البريد الإلكتروني. لم يتم إرسال رمز جديد — إعادة الإرسال بعد '
          '${_secondsAr(remaining)}.';
    }
    return 'تم تحديث البريد الإلكتروني. لم يتم إرسال رمز جديد.';
  }

  String _resendLabel(_ChannelRowState row) {
    if (row.resendExhausted) return 'تم استنفاد إعادة الإرسال';
    final remaining = row.resendSecondsRemaining;
    if (remaining != null && remaining > 0) return 'إعادة الإرسال بعد ${_secondsAr(remaining)}';
    return 'إعادة إرسال الرمز';
  }

}
