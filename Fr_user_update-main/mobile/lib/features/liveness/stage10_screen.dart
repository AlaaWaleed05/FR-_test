import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/entry_models.dart' show BackendUnreachableException;
import '../../core/journey/journey_error.dart';
import '../../core/liveness/liveness_models.dart';
import '../../core/liveness/liveness_providers.dart';
import '../../core/liveness/uqudo_face_scanner.dart';
import '../entry/offline_banner.dart';
import '../../core/widgets/blocked_view.dart';
import '../../core/widgets/screen_title.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/journey_progress.dart';

/// What Stage 10 is currently showing. One screen, several states — the customer never leaves the
/// stage for an error, they are told what happened and offered the action that fits.
enum _Stage10View {
  loading,

  /// customer.md's preparation screen. It sits in front of the SDK's own liveness UI and does not
  /// duplicate it.
  preparation,

  /// A token is being minted, the face session is up, or a capture is uploading. The SDK draws its
  /// own full-screen UI over this.
  ///
  /// The wait is bounded: [uqudoFaceNoAnswerTimeout] guarantees an SDK that never answers still
  /// resolves this state rather than spinning forever.
  working,

  /// The check did not work, and **an attempt was spent** — the SDK produced nothing, the backend
  /// refused the JWS, or the face did not match.
  ///
  /// Reached only from outcomes that genuinely cost the customer an attempt. A malformed request or
  /// an unrecognised future code costs nothing and lands on [clientError] instead, because
  /// `mapJourneyError` deliberately degrades an unknown coded 400 AWAY from "an attempt was spent"
  /// and this screen must not put it back.
  failed,

  /// The request was wrong, or the answer was one this app version does not understand.
  /// **No attempt spent**, and the copy says so.
  clientError,

  /// A capture is retained and its upload failed. Retryable without repeating the check.
  uploadRetry,

  /// The retained capture can no longer be used — `ARTIFACT_EXPIRED`/`AUDIT_TRAIL_UNAVAILABLE`.
  artifactUnusable,

  /// The 24-hour block.
  blocked,

  /// The SDK never reached a camera. **No attempt spent.**
  cameraDenied,

  /// A token request or upload failed for connectivity reasons. **No attempt spent.**
  connectivity,
}

/// Journey Stage 10 — liveness and face matching (docs/journeys/customer.md).
///
/// **One operation, two questions.** Uqudo's face session answers "is this a live person" and "is
/// this the same person as the portrait from the scanned document" together. The second is what
/// binds the person to the document; without it a genuine document held by someone else passes the
/// journey.
///
/// **The customer sees one outcome; the record does not.** customer.md is explicit that "the
/// customer sees one outcome: it worked, or it did not" — there is no "the check is wrong" path
/// here, unlike Stage 9, because there is nothing to disagree with. No match level and no attempt
/// count appear anywhere in this file.
///
/// **No attempt count is displayed, and none is counted locally.** `attemptsRemaining` is on no
/// liveness wire and this app does not invent one — R-052's named failure mode. The customer learns
/// their budget is spent from a server-sent `LIVENESS_BLOCKED`, never from arithmetic done here.
///
/// **The device holds no Uqudo credentials.** The access token is minted per session by the backend
/// at the moment of tapping, lives in one local inside `LivenessRepository.runFaceCheck`, and is
/// never stored or logged.
class Stage10Screen extends ConsumerStatefulWidget {
  const Stage10Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage10Screen> createState() => _Stage10ScreenState();
}

class _Stage10ScreenState extends ConsumerState<Stage10Screen> {
  _Stage10View _view = _Stage10View.loading;
  DateTime? _blockedUntil;

  @override
  void initState() {
    super.initState();
    unawaited(_resolveEntryState());
  }

  /// Decides between offering "retry the upload" and starting fresh.
  ///
  /// The retained-capture guard is here rather than in the store, for the reason
  /// [RetainedFaceStore] gives: the store is process-scoped and `EntryRepository` abandonment does
  /// not clear it, so a capture can outlive the session it belongs to. Re-offering one whose
  /// `profileId` no longer matches would post the previous session's capture.
  ///
  /// **Matched on `profileId` alone.** Stage 8 additionally compares `documentType`, because a
  /// customer can change document type at Stage 7 and a capture of the old one must not be
  /// re-offered. Stage 10 has no equivalent second key: on entry this screen holds no
  /// `faceSessionId` to compare against — the backend mints one per attempt, at the moment of
  /// tapping, so any id worth checking does not exist yet when this guard runs. A retained capture
  /// whose `profileId` matches is by construction the only capture this session could re-send.
  Future<void> _resolveEntryState() async {
    final repo = ref.read(livenessRepositoryProvider);
    try {
      final retained = repo.retainedFace;
      if (retained != null) {
        final profileId = await repo.requireProfileId();
        if (retained.profileId == profileId) {
          if (!mounted) return;
          setState(() => _view = _Stage10View.uploadRetry);
          return;
        }
        repo.discardRetainedFace();
      }
    } on Object {
      // A missing local session is not this screen's problem to solve — fall through to
      // preparation, where the first action re-reads it and surfaces the real error.
    }
    if (!mounted) return;
    setState(() => _view = _Stage10View.preparation);
  }

  Future<void> _begin() async {
    setState(() => _view = _Stage10View.working);
    final repo = ref.read(livenessRepositoryProvider);
    try {
      _applyResult(await repo.runFaceCheck());
    } on FaceAttemptTerminated catch (e) {
      await _handleTerminated(e);
    } on LivenessRejectedException {
      // A coded 400: the backend examined the capture and refused it, and an attempt IS gone. The
      // capture is already cleared by the repository — retrying the same bytes would fail
      // identically.
      if (!mounted) return;
      setState(() => _view = _Stage10View.failed);
    } on JourneyConflictException catch (e) {
      if (!mounted) return;
      _applyConflict(e);
    } on BackendUnreachableException {
      // customer.md: "Token or upload failure → connectivity, not liveness. Not counted." Any 5xx
      // lands here too, including the unmapped 500 when Uqudo's own token endpoint is down.
      if (!mounted) return;
      setState(
        () => _view = repo.retainedFace != null
            ? _Stage10View.uploadRetry
            : _Stage10View.connectivity,
      );
    } on JourneyClientErrorException {
      if (!mounted) return;
      setState(() => _view = _Stage10View.clientError);
    } on JourneyProfileNotFoundException {
      if (!mounted) return;
      setState(() => _view = _Stage10View.clientError);
    } on Object {
      if (!mounted) return;
      setState(() => _view = _Stage10View.failed);
    }
  }

  Future<void> _retryUpload() async {
    setState(() => _view = _Stage10View.working);
    final repo = ref.read(livenessRepositoryProvider);
    try {
      _applyResult(await repo.retryUpload());
    } on LivenessRejectedException {
      if (!mounted) return;
      setState(() => _view = _Stage10View.failed);
    } on JourneyConflictException catch (e) {
      if (!mounted) return;
      _applyConflict(e);
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => _view = _Stage10View.uploadRetry);
    } on JourneyClientErrorException {
      if (!mounted) return;
      setState(() => _view = _Stage10View.clientError);
    } on JourneyProfileNotFoundException {
      if (!mounted) return;
      setState(() => _view = _Stage10View.clientError);
    } on Object {
      if (!mounted) return;
      setState(() => _view = _Stage10View.failed);
    }
  }

  /// The no-JWS paths — an explicit cancel, the SDK exhausting its internal liveness retries, and
  /// the silent-SDK timeout alike.
  ///
  /// **Reporting is what spends the attempt**, so the one case that owes nothing is filtered here
  /// and never reported: a session that died on a missing camera permission consumed no Uqudo
  /// operation, and charging it against a five-attempt budget would charge the customer for a
  /// device setting they can fix.
  ///
  /// Everything else is reported, and the report is followed by a pointer read — see
  /// `LivenessRepository.reportTerminated` for why a bare ack is not enough to know whether this
  /// was the attempt that triggered the block.
  Future<void> _handleTerminated(FaceAttemptTerminated terminated) async {
    if (terminated.cameraUnavailable) {
      if (!mounted) return;
      setState(() => _view = _Stage10View.cameraDenied);
      return;
    }
    try {
      final blockedUntil = await ref
          .read(livenessRepositoryProvider)
          .reportTerminated(
            faceSessionId: terminated.faceSessionId,
            sdkErrorCode: terminated.sdkErrorCode,
            partialJws: terminated.partialJws,
          );
      if (!mounted) return;
      setState(() {
        _blockedUntil = blockedUntil;
        _view = blockedUntil != null
            ? _Stage10View.blocked
            : _Stage10View.failed;
      });
    } on JourneyConflictException catch (e) {
      if (!mounted) return;
      _applyConflict(e);
    } on Object {
      // The attempt happened whether or not we managed to record it. Showing the ordinary failure
      // screen is honest; claiming success or connectivity would not be.
      if (!mounted) return;
      setState(() => _view = _Stage10View.failed);
    }
  }

  void _applyResult(FaceResult result) {
    if (!mounted) return;
    if (result.passed) {
      // customer.md Stage 10: "Passes both liveness and face match → stage 11."
      // `offline` is carried forward: it is this launch's own connectivity result and lives
      // nowhere else, so dropping it here would silently switch the banner off mid-journey.
      context.go('/stage-11', extra: widget.offline);
      return;
    }
    // A judged failure. The attempt is already spent, and `blockedUntil` is non-null exactly when
    // it was the fifth — no probe needed on this path, unlike `/terminated`.
    setState(() {
      _blockedUntil = result.blockedUntil;
      _view = result.blockedUntil != null
          ? _Stage10View.blocked
          : _Stage10View.failed;
    });
  }

  void _applyConflict(JourneyConflictException conflict) {
    switch (conflict.code) {
      case JourneyCode.livenessBlocked:
        setState(() {
          _blockedUntil = conflict.blockedUntil;
          _view = _Stage10View.blocked;
        });
      case JourneyCode.livenessAlreadyPassed:
        // Stage 10 is done — the customer belongs on Stage 11. Not an error to show them.
        context.go('/stage-11', extra: widget.offline);
      case JourneyCode.artifactExpired:
      case JourneyCode.auditTrailUnavailable:
        setState(() => _view = _Stage10View.artifactUnusable);
      case JourneyCode.rescanRequired:
        // The accepted cycle's reference portrait was purged, so there is nothing to match against.
        context.go('/stage-8', extra: widget.offline);
      case JourneyCode.registryPending:
        context.go('/stage-9', extra: widget.offline);
      case JourneyCode.profileTerminal:
        context.go('/ended');
      case JourneyCode.stateConflict:
      case JourneyCode.unknown:
      default:
        // The app's idea of where the customer is disagrees with the backend's, or the code is one
        // this version does not know. Both recover the same way: re-read the pointer and land
        // wherever it says, rather than guessing a screen.
        context.go('/final-stages', extra: widget.offline);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(title: const ScreenTitle('التحقق الشخصي')),
      body: SafeArea(
        child: Column(
          children: [
            const JourneyProgress(step: JourneyStep.liveness),
            if (widget.offline) const OfflineBanner(),
            Expanded(child: _body()),
          ],
        ),
      ),
    );
  }

  Widget _body() {
    switch (_view) {
      case _Stage10View.loading:
      case _Stage10View.working:
        return const Center(child: CircularProgressIndicator());
      case _Stage10View.preparation:
        return _preparation();
      case _Stage10View.failed:
        return _message(
          title: 'لم يكتمل التحقق',
          body: 'لم نتمكن من التحقق هذه المرة. يمكنك المحاولة مرة أخرى.',
          actionLabel: 'إعادة المحاولة',
          onAction: _begin,
        );
      case _Stage10View.uploadRetry:
        return _message(
          title: 'تعذر إرسال نتيجة التحقق',
          // The window here is ~10 minutes, NOT Stage 8's two hours: Uqudo deletes the face session
          // and its reference image after 600 seconds. Saying "shortly" rather than promising a
          // duration avoids both an over-promise and a countdown this screen cannot police.
          body:
              'تم التحقق بنجاح، لكن تعذر إرسال النتيجة. يمكنك إعادة الإرسال الآن دون إعادة التحقق. '
              'يرجى المحاولة خلال دقائق قليلة.',
          actionLabel: 'إعادة الإرسال',
          onAction: _retryUpload,
        );
      case _Stage10View.artifactUnusable:
        return _message(
          title: 'انتهت صلاحية التحقق',
          body:
              'انتهت صلاحية هذه المحاولة ولم يعد بالإمكان استخدامها. يرجى إعادة التحقق.',
          actionLabel: 'إعادة التحقق',
          onAction: _begin,
        );
      case _Stage10View.cameraDenied:
        return _message(
          title: 'تعذر الوصول إلى الكاميرا',
          // Stated plainly, because it is true and it is the difference between a customer who
          // fixes a setting and one who thinks they wasted a try.
          body:
              'يحتاج التطبيق إلى إذن الكاميرا لإتمام التحقق. يمكنك منح الإذن من إعدادات الجهاز ثم '
              'المحاولة مرة أخرى. لم تُحتسب هذه المحاولة.',
          actionLabel: 'إعادة المحاولة',
          onAction: _begin,
        );
      case _Stage10View.clientError:
        return _message(
          title: 'تعذر إتمام التحقق',
          // Says "no attempt was counted" for the same reason the connectivity and camera screens
          // do: the customer's budget is the thing they cannot see, so silence about it reads as
          // a loss.
          body: 'حدث خطأ غير متوقع. يمكنك المحاولة مرة أخرى. لم تُحتسب هذه المحاولة.',
          actionLabel: 'إعادة المحاولة',
          onAction: _begin,
        );
      case _Stage10View.connectivity:
        return _message(
          title: 'تعذر الاتصال',
          body:
              'يرجى التحقق من الاتصال والمحاولة مرة أخرى. لم تُحتسب هذه المحاولة.',
          actionLabel: 'إعادة المحاولة',
          onAction: _begin,
        );
      case _Stage10View.blocked:
        return BlockedView(
          title: 'التحقق من الهوية غير متاح مؤقتًا',
          blockedUntil: _blockedUntil,
          onRecheck: _begin,
          // BL-109: Stage 10 was the one block screen in the journey with no way out. Stage 8
          // (`stage8_screen.dart`) and Stage 9 both pass this; Stage 10 shipped without it, and
          // the screen is reached by `context.go`, so there was no AppBar back either.
          onLeave: () => context.go('/'),
        );
    }
  }

  /// customer.md's preparation screen, and it earns its place: "unlike stage 8, this screen
  /// materially affects success rates. Liveness failures are overwhelmingly environmental —
  /// backlight, low light, angle — and a customer told to move somewhere brighter *before* starting
  /// often passes first time."
  ///
  /// Provisional content, per customer.md's own note: what the SDK instructs, and in which language,
  /// is only partly known (R-002 — the AAR ships no Arabic, so the SDK's own UI is English on the
  /// demo). This sits in front of that and does not duplicate it.
  Widget _preparation() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'سنطلب منك النظر إلى الكاميرا للتأكد من أنك أنت صاحب الوثيقة التي تم مسحها.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 20),
          ...const [
            'اختر مكانًا بإضاءة جيدة ومتساوية.',
            'اجعل الضوء أمام وجهك، لا خلف ظهرك.',
            'أمسك الهاتف على مستوى العينين.',
            'انزع النظارات الشمسية، وتجنب ظل القبعة على وجهك.',
            'تأكد من عدم وجود أشخاص آخرين في الصورة.',
          ].map(
            (line) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('• '),
                  Expanded(child: Text(line)),
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),
          // customer.md: the preparation screen must say what is captured and that it is stored.
          const Text(
            'سيتم التقاط صورة لوجهك وحفظها مع ملفك لدى البنك.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 24),
          FilledButton(onPressed: _begin, child: const Text('ابدأ التحقق')),
        ],
      ),
    );
  }

  Widget _message({
    required String title,
    required String body,
    required String actionLabel,
    required VoidCallback onAction,
  }) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            title,
            style: Theme.of(context).textTheme.titleMedium,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          Text(body, textAlign: TextAlign.center),
          const SizedBox(height: 24),
          FilledButton(onPressed: onAction, child: Text(actionLabel)),
        ],
      ),
    );
  }
}
