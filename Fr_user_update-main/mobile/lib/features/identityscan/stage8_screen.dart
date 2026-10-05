import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/dataentry/data_entry_providers.dart';
import '../../core/entry/entry_models.dart'
    show BackendUnreachableException, IdentityScanStage, ProfileAlreadyCompleteException;
import '../../core/identityscan/identity_scan_models.dart';
import '../../core/identityscan/identity_scan_providers.dart';
import '../../core/identityscan/uqudo_scanner.dart';
import '../entry/offline_banner.dart';
import '../../core/widgets/blocked_view.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/journey_progress.dart';

/// What Stage 8 is currently showing. One screen, several states — the customer never leaves the
/// stage for an error, they are told what happened and offered the action that fits.
enum _Stage8View {
  loading,
  preparation,

  /// A token is being minted, the SDK is up, or a capture is uploading. The SDK draws its own
  /// full-screen UI over this.
  ///
  /// The wait is bounded: [uqudoScanNoAnswerTimeout] guarantees an SDK that never answers still
  /// resolves this state rather than spinning forever (F-1).
  working,

  /// The scan did not work — the SDK produced nothing, or the backend examined it and refused it.
  /// **An attempt was spent** in both cases.
  rejected,

  /// A capture is retained and its upload failed (BL-034). Retryable without rescanning.
  uploadRetry,

  /// The retained capture can no longer be used — `ARTIFACT_EXPIRED`/`IMAGES_UNAVAILABLE`.
  artifactUnusable,

  /// This document type's budget is spent; the other type still has its own.
  typeExhausted,

  /// The 24-hour block.
  blocked,

  /// The SDK never reached a camera. **No attempt spent** — see [UqudoScanFailure].
  cameraDenied,

  /// A token request or upload failed for connectivity reasons. **No attempt spent.**
  connectivity,
}

/// Journey Stage 8 — document scan (docs/journeys/customer.md).
///
/// **The preparation screen carries real weight.** customer.md: "cancelling out of the scan counts
/// as an attempt ... It is the customer's only free opportunity to get set up before the retry
/// budget starts being consumed, so the guidance must be genuinely useful rather than decorative."
/// It sits in front of the SDK's own instructions and does not duplicate them.
///
/// **The sentence naming the attempt cost is deliberately absent.** Walk comment 8b called it
/// meaningless; S8-05 kept it and restyled it instead, and the product owner asked a second time
/// for it gone. Removed at S8-09 on that decision. The consequence is recorded on BL-090 rather
/// than argued here: nothing on the journey now tells the customer that backing out of the camera
/// spends one of BL-039's five per-type attempts, so the 24-hour block arrives unexplained. Do not
/// reinstate it without the product owner.
///
/// **The app is in the loop but blind.** It holds the camera flow because capture needs one; it
/// forwards the JWS untouched because verification is server-side only (CLAUDE.md). Both at once.
///
/// **No attempt count is displayed anywhere in this file.** `attemptsRemaining` is not on the wire
/// and this app does not count locally — that is R-052's whole subject. The customer learns their
/// budget is spent from `SCAN_TYPE_EXHAUSTED` and `SCAN_BLOCKED`, which are server-sent.
class Stage8Screen extends ConsumerStatefulWidget {
  const Stage8Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage8Screen> createState() => _Stage8ScreenState();
}

class _Stage8ScreenState extends ConsumerState<Stage8Screen> {
  _Stage8View _view = _Stage8View.loading;
  String? _documentType;
  DateTime? _blockedUntil;
  String? _detail;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final draft = await ref.read(dataEntryRepositoryProvider).loadDraft();
    if (!mounted) return;
    final documentType = draft?.identityType;
    if (documentType == null || !IdentityDocumentTypes.isValid(documentType)) {
      // Nothing to scan without a chosen type. Reachable if a resume pointer runs ahead of the
      // draft; the honest fix is to send the customer back to the screen that sets it.
      context.go('/stage-7');
      return;
    }
    // A capture retained from earlier in this process — the customer navigated away and back, or
    // an upload failed and they returned. Offer the retry rather than a fresh scan, because a
    // fresh scan would cost an attempt this capture has already paid for.
    //
    // But only if it still belongs to what is on screen now. The store is process-scoped, so it
    // outlives the thing it was captured for: a customer who switched document type at Stage 7
    // would otherwise be offered a re-send of the PREVIOUS document's JWS (leaving the profile's
    // Stage 7 answer and the accepted cycle disagreeing, with nothing on either tier to reconcile
    // them), and one who abandoned and started a new session would be offered a re-send carrying
    // the old `profileId`. Both found by `@agent-reviewer` against this diff.
    final repository = ref.read(identityScanRepositoryProvider);
    final retained = repository.retainedScan;
    final profileId = await repository.requireProfileId();
    if (!mounted) return;
    final retainedIsUsable =
        retained != null &&
        retained.profileId == profileId &&
        retained.documentType == documentType;
    if (retained != null && !retainedIsUsable) {
      repository.discardRetainedScan();
    }

    setState(() {
      _documentType = documentType;
      _view = retainedIsUsable ? _Stage8View.uploadRetry : _Stage8View.preparation;
    });
  }

  /// Walk comment 8 (2026-09-10): «بطاقة الرقم الوطني» named the document after the NUMBER
  /// printed on it. The bank's own term is «البطاقة القومية», and Stage 7's card now uses it, so
  /// this follows in the same commit — the two screens name the same document one after the other
  /// and must not disagree.
  String get _documentLabel => _documentType == IdentityDocumentTypes.passport
      ? 'جواز السفر'
      : 'البطاقة القومية';

  // ---- actions ---------------------------------------------------------------------------------

  /// One instruction line: a Material glyph and its text on a single row (walk comment 8a).
  ///
  /// `Row` lays out along the ambient `Directionality`, so under this app's RTL the icon lands on
  /// the right with no `textDirection` argument and no hardcoded alignment — the same
  /// direction-neutral-by-construction rule `app_theme.dart` applies to the page transition.
  /// `crossAxisAlignment: start` keeps the glyph beside the FIRST line when the text wraps, which
  /// it will at a larger text scale, instead of floating to the vertical centre of the block.
  Widget _instruction(IconData icon, String text) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: theme.colorScheme.primary),
          const SizedBox(width: 12),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }

  Future<void> _startScan() async {
    setState(() {
      _view = _Stage8View.working;
      _detail = null;
    });
    final repository = ref.read(identityScanRepositoryProvider);
    try {
      await repository.runScan(_documentType!);
      await _goToReview();
    } on UqudoScanFailure catch (failure) {
      if (!mounted) return;
      if (failure.isCameraUnavailable) {
        // No Uqudo operation was consumed, so `/cancel` is deliberately NOT called and no attempt
        // is spent. Charging the budget for a device permission the customer can fix in Settings
        // would burn a five-attempt budget (ScanAttemptBudget.PER_TYPE_LIMIT) on something that is
        // not a scan.
        setState(() => _view = _Stage8View.cameraDenied);
        return;
      }
      // Everything else — USER_CANCEL, SESSION_EXPIRED, UNEXPECTED_ERROR, REQUEST_TIMEOUT — is a
      // launched session that consumed a real Uqudo operation. customer.md: "a cancel is not
      // free". Recording it is what actually spends the attempt.
      await _spendAttemptForAbandonedSession();
    } on UqudoScanNoResponse {
      if (!mounted) return;
      // F-1: the SDK was launched and never answered. It costs exactly what a cancel costs, because
      // customer.md:656-659 keys the charge on a session having been LAUNCHED — "a launched SDK
      // session consumes a real Uqudo operation whether or not a document was captured" — and not
      // on which status code came back. There is no way to say otherwise even if we wanted to:
      // `CancelScanRequest` carries only profileId + documentType, so "silent native" and
      // "USER_CANCEL" are not distinguishable on the wire.
      //
      // This arm exists SEPARATELY from the catch-all below on purpose. Falling through to
      // `catch (_)` would show the connectivity screen, whose body reads "لم يتم احتساب أي محاولة"
      // — telling the customer no attempt was counted immediately after this code spent one. That
      // is the same false-budget statement `@agent-reviewer` already removed from the unreachable
      // -backend branch of `_spendAttemptForAbandonedSession`.
      await _spendAttemptForAbandonedSession();
    } on ScanRejectedException {
      if (!mounted) return;
      setState(() {
        _view = _Stage8View.rejected;
        _detail = null;
      });
    } on ScanConflictException catch (conflict) {
      if (!mounted) return;
      _applyConflict(conflict);
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() {
        // If a capture is retained, the token and the scan both succeeded and only the upload was
        // lost — an entirely different situation from a failed token request, and the one BL-034
        // exists for.
        _view = ref.read(identityScanRepositoryProvider).retainedScan != null
            ? _Stage8View.uploadRetry
            : _Stage8View.connectivity;
      });
    } on ProfileAlreadyCompleteException {
      if (!mounted) return;
      _goToTerminal();
    } on ProfileNotFoundException {
      if (!mounted) return;
      _resync();
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _view = _Stage8View.connectivity;
        _detail = 'حدث خطأ غير متوقع.';
      });
    }
  }

  /// Files the abandoned SDK session so the backend spends the attempt.
  ///
  /// The cancel call can itself refuse: it is the attempt that may exhaust the budget, so a
  /// `SCAN_BLOCKED` here is the normal way a customer discovers the 24-hour block.
  Future<void> _spendAttemptForAbandonedSession() async {
    try {
      await ref.read(identityScanRepositoryProvider).cancelScan(_documentType!);
      if (!mounted) return;
      setState(() => _view = _Stage8View.rejected);
    } on ScanConflictException catch (conflict) {
      if (!mounted) return;
      _applyConflict(conflict);
    } on BackendUnreachableException {
      if (!mounted) return;
      // The attempt could not be filed. The backend owns the budget, so an unrecorded attempt
      // simply was not spent — and the `rejected` screen says outright that one WAS, which would
      // state as fact something this branch knows did not happen. The connectivity screen is both
      // true and the more useful thing to show. Found by `@agent-reviewer` against this diff.
      setState(() {
        _view = _Stage8View.connectivity;
        _detail = 'تعذر إكمال المسح.';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _view = _Stage8View.rejected);
    }
  }

  Future<void> _retryUpload() async {
    setState(() {
      _view = _Stage8View.working;
      _detail = null;
    });
    try {
      await ref.read(identityScanRepositoryProvider).retryUpload();
      await _goToReview();
    } on ScanRejectedException {
      if (!mounted) return;
      setState(() => _view = _Stage8View.rejected);
    } on ScanConflictException catch (conflict) {
      if (!mounted) return;
      _applyConflict(conflict);
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => _view = _Stage8View.uploadRetry);
    } on ProfileAlreadyCompleteException {
      if (!mounted) return;
      _goToTerminal();
    } catch (_) {
      if (!mounted) return;
      setState(() => _view = _Stage8View.uploadRetry);
    }
  }

  /// Maps one BL-033 code to the screen customer.md specifies for it. This is R-052's consumer.
  void _applyConflict(ScanConflictException conflict) {
    switch (conflict.code) {
      case ScanConflictCode.scanBlocked:
        setState(() {
          _view = _Stage8View.blocked;
          _blockedUntil = conflict.blockedUntil;
        });
      case ScanConflictCode.scanTypeExhausted:
        setState(() => _view = _Stage8View.typeExhausted);
      case ScanConflictCode.artifactExpired:
      case ScanConflictCode.imagesUnavailable:
        setState(() => _view = _Stage8View.artifactUnusable);
      case ScanConflictCode.registryPending:
        // The scan is already accepted and only the lookup is outstanding — the customer belongs
        // on Stage 9's pause screen, not here. Reached by re-submitting after a lost
        // acknowledgement whose scan had in fact landed.
        unawaited(_goToReview());
      case ScanConflictCode.profileTerminal:
        _goToTerminal();
      case ScanConflictCode.stateConflict:
        unawaited(_resync());
      case ScanConflictCode.registryNotReady:
      case ScanConflictCode.unknown:
        // `REGISTRY_NOT_READY` belongs to Stage 9's actions and should not arise here; an unknown
        // code is a backend this app version does not fully know. Both are handled by re-reading
        // the authoritative state rather than by guessing a screen.
        unawaited(_resync());
    }
  }

  Future<void> _goToReview() async {
    await ref.read(identityScanRepositoryProvider).advanceToStage(IdentityScanStage.stage9.name);
    if (!mounted) return;
    context.go('/stage-9');
  }

  /// `STATE_CONFLICT`'s advice, actually performed. S5-11's read-only endpoint is what makes this
  /// possible: `POST /registry-review/current` reads the stored Stage 9 payload with no registry
  /// call and no write, so "the app and the backend disagree" has a real recovery instead of a
  /// dead end. If that read also refuses, the disagreement is further back than Stage 9 and the
  /// customer restarts the scan from the type selection.
  Future<void> _resync() async {
    setState(() => _view = _Stage8View.working);
    try {
      await ref.read(identityScanRepositoryProvider).currentReview();
      await _goToReview();
    } catch (_) {
      if (!mounted) return;
      context.go('/stage-7');
    }
  }

  void _goToTerminal() {
    context.go('/ended');
  }

  void _changeDocument() {
    // The resume pointer is deliberately NOT moved back. Back-navigation never moves it anywhere
    // in this app (S5-05's documented choice) — a relaunch resumes at the furthest stage reached,
    // and Stage 7 stays reachable from here.
    context.go('/stage-7');
  }

  // ---- rendering -------------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(title: const Text('مسح وثيقة الهوية')),
      // Walk comment 9 (walk of 2026-09-10). This screen's actions live INSIDE the body — its
      // view switches between preparation, message and working blocks — so there is no single
      // action row to hold clear and the whole body takes the inset. `top: false`: the app bar
      // has already consumed that one.
      //
      // **PRECAUTIONARY, not a fix for an observed defect, and deliberately recorded as such.**
      // Every one of this screen's views is top-aligned or centred, so its buttons never actually
      // reached the navigation bar — proved by reverting this `SafeArea` and watching the Stage 8
      // suite stay green. It is here for a short viewport at a large text scale, and for the next
      // view added to `_body` that IS bottom-anchored. Stage 9's review view is the one in this
      // pair that genuinely was flush, and that is where the regression test lives.
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            const JourneyProgress(step: JourneyStep.documentScan),
            if (widget.offline) const OfflineBanner(),
            Expanded(child: _body(context)),
          ],
        ),
      ),
    );
  }

  Widget _body(BuildContext context) {
    switch (_view) {
      case _Stage8View.loading:
      case _Stage8View.working:
        return const Center(child: CircularProgressIndicator());
      case _Stage8View.preparation:
        return _preparation(context);
      case _Stage8View.rejected:
        return _message(
          context,
          title: 'لم ينجح مسح الوثيقة',
          // Honest about the cost, without inventing a remaining count.
          body: 'تم احتساب محاولة. تأكد من الإضاءة الجيدة، ومن وضع الوثيقة كاملة داخل الإطار '
              'على سطح مستوٍ وبدون انعكاس للضوء.',
          primaryLabel: 'إعادة المحاولة',
          onPrimary: _startScan,
          secondaryLabel: 'تغيير الوثيقة',
          onSecondary: _changeDocument,
        );
      case _Stage8View.uploadRetry:
        return _message(
          context,
          title: 'تم مسح الوثيقة، ولم يكتمل الإرسال',
          // The point the customer cares about: they do not have to scan again.
          body: 'الوثيقة التي مسحتها محفوظة ولا حاجة لإعادة المسح. تحقق من الاتصال ثم أعد الإرسال.',
          primaryLabel: 'إعادة الإرسال',
          onPrimary: _retryUpload,
          // An escape hatch, added after the flow test found this state had none: a customer whose
          // connection never returns could otherwise only leave by killing the app. Starting over
          // is a real choice, and the retained capture is simply superseded by the next scan.
          secondaryLabel: 'تغيير الوثيقة',
          onSecondary: _changeDocument,
        );
      case _Stage8View.artifactUnusable:
        return _message(
          context,
          title: 'انتهت صلاحية المسح السابق',
          // customer.md Stage 13 requires distinguishing "retrying an upload" from "this capture
          // can no longer be used", and saying plainly which has happened.
          body: 'لم يعد بالإمكان استخدام المسح السابق، ويلزم مسح الوثيقة من جديد.',
          primaryLabel: 'مسح الوثيقة من جديد',
          onPrimary: _startScan,
          secondaryLabel: 'تغيير الوثيقة',
          onSecondary: _changeDocument,
        );
      case _Stage8View.typeExhausted:
        return _message(
          context,
          title: 'تعذر مسح $_documentLabel',
          // customer.md: "the customer may still switch to the other document type with a fresh
          // per-type budget. A passport is a genuinely different attempt, not a fourth try at a
          // failing national ID."
          body: 'يمكنك استخدام وثيقة أخرى لإثبات هويتك.',
          primaryLabel: 'اختيار وثيقة أخرى',
          onPrimary: _changeDocument,
        );
      case _Stage8View.blocked:
        return BlockedView(
          blockedUntil: _blockedUntil,
          onRecheck: _startScan,
          onLeave: () => context.go('/'),
        );
      case _Stage8View.cameraDenied:
        return _message(
          context,
          title: 'لا يمكن الوصول إلى الكاميرا',
          body: 'يحتاج مسح الوثيقة إلى إذن استخدام الكاميرا. امنح التطبيق هذا الإذن من إعدادات '
              'الجهاز ثم أعد المحاولة. لم يتم احتساب أي محاولة.',
          primaryLabel: 'إعادة المحاولة',
          onPrimary: _startScan,
        );
      case _Stage8View.connectivity:
        return _message(
          context,
          title: 'تعذر الاتصال',
          // customer.md is explicit that a token/upload connectivity failure is NOT counted
          // against the budget, so the screen says so — otherwise the customer reasonably assumes
          // they have just lost one of three attempts to a network problem.
          body: '${_detail ?? 'تعذر الاتصال.'} لم يتم احتساب أي محاولة. '
              'تحقق من الاتصال ثم أعد المحاولة.',
          primaryLabel: 'إعادة المحاولة',
          onPrimary: _startScan,
        );
    }
  }

  Widget _preparation(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'أنت على وشك مسح $_documentLabel',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 16),
          // Deliberately practical rather than decorative, and deliberately NOT a restatement of
          // the SDK's own on-screen instructions, which come next and are Uqudo's to write.
          const Text('قبل أن تبدأ:'),
          const SizedBox(height: 8),
          // Walk comment 8a. The bullet glyph carried no meaning beyond "this is a list"; an icon
          // per instruction says WHICH instruction at a glance, which matters on a mixed-literacy
          // audience's last screen before a camera opens. The WORDS are untouched — 8a asked for
          // icons to be added, not for the guidance to be rewritten.
          //
          // `_instruction` renders the icon and text as one RTL row, so the icon sits on the
          // leading (right) side under Arabic without any of these call sites naming a side.
          _instruction(Icons.wb_sunny_outlined, 'اجلس في مكان جيد الإضاءة، وتجنب الضوء المباشر الذي يسبب انعكاسًا.'),
          _instruction(Icons.crop_free, 'ضع الوثيقة على سطح مستوٍ وداكن اللون إن أمكن.'),
          _instruction(Icons.fullscreen, 'أدخل الوثيقة كاملة داخل الإطار، بما في ذلك حوافها.'),
          _instruction(Icons.camera_alt_outlined, 'امسح الغبار أو البصمات عن عدسة الكاميرا.'),
          const Spacer(),
          FilledButton(onPressed: _startScan, child: const Text('ابدأ المسح')),
          const SizedBox(height: 12),
          OutlinedButton(onPressed: _changeDocument, child: const Text('تغيير الوثيقة')),
        ],
      ),
    );
  }

  Widget _message(
    BuildContext context, {
    required String title,
    required String body,
    required String primaryLabel,
    required VoidCallback onPrimary,
    String? secondaryLabel,
    VoidCallback? onSecondary,
  }) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium, textAlign: TextAlign.center),
          const SizedBox(height: 12),
          Text(body, textAlign: TextAlign.center),
          const SizedBox(height: 24),
          FilledButton(onPressed: onPrimary, child: Text(primaryLabel)),
          if (secondaryLabel != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onSecondary, child: Text(secondaryLabel)),
          ],
        ],
      ),
    );
  }
}
