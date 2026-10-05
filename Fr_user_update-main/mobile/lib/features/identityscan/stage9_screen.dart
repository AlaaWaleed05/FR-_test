import 'dart:async' show unawaited;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/entry_models.dart'
    show
        BackendUnreachableException,
        IdentityScanStage,
        ProfileAlreadyCompleteException,
        resumeStageBeyondStage9;
import '../../core/identityscan/identity_scan_models.dart';
import '../../core/identityscan/identity_scan_providers.dart';
import '../../core/images/decode_width.dart';
import '../../core/text/display_date.dart';
import '../../core/text/ltr_value.dart';
import '../entry/offline_banner.dart';
import '../../core/widgets/blocked_view.dart';
import '../../core/widgets/screen_title.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/journey_progress.dart';

enum _Stage9View {
  loading,

  /// The registry data is present and the customer is deciding.
  review,

  /// `registryReady == false` — the lookup has not produced a result. customer.md: "The session
  /// pauses. It does not fail."
  paused,

  /// An action was refused with `SCAN_BLOCKED`.
  blocked,

  /// Nothing could be loaded — connectivity, or an unexpected failure.
  unavailable,
}

/// Journey Stage 9 — Civil Registry review (docs/journeys/customer.md).
///
/// **Nothing on this screen is editable.** The data is authoritative by design; the customer's job
/// is to check one value — the national number — against the document in their hand.
///
/// **The screen always loads through `POST /registry-review/current`** (S5-11), both when arriving
/// from Stage 8 and when resuming into it. That endpoint reads what is stored: no Civil Registry
/// call, no write. Re-triggering a lookup on arrival would overwrite a stored result every time
/// the customer returned, which is exactly the defect S5-11 was built to remove. The cost is one
/// extra round trip after a successful scan, and it buys a single code path for both entries —
/// matching `LaunchScreen`'s own doctrine that every destination re-reads its values rather than
/// trusting what navigation carried.
class Stage9Screen extends ConsumerStatefulWidget {
  const Stage9Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage9Screen> createState() => _Stage9ScreenState();
}

class _Stage9ScreenState extends ConsumerState<Stage9Screen> {
  _Stage9View _view = _Stage9View.loading;
  ScanDisplay? _display;
  DateTime? _blockedUntil;
  String? _errorMessage;
  bool _acting = false;

  /// Fetched once per load and held only in memory. These are identity-document images: the
  /// endpoint sends `Cache-Control: no-store` precisely so nothing keeps a copy the journey cannot
  /// later purge, and nothing here writes them to disk.
  final Map<String, Uint8List> _images = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _view = _Stage9View.loading;
      _errorMessage = null;
    });
    final repository = ref.read(identityScanRepositoryProvider);
    try {
      final display = await repository.currentReview();
      if (!mounted) return;
      setState(() {
        _display = display;
        _view = display.registryReady ? _Stage9View.review : _Stage9View.paused;
      });
      if (display.registryReady) unawaited(_loadImages(display));
    } on ScanConflictException catch (conflict) {
      if (!mounted) return;
      // `duringLoad: true` is what stops the re-sync arm from calling this loader again. Reached
      // for real, not only in a race: `currentReviewPayload` answers STATE_CONFLICT whenever there
      // is no accepted snapshot, and `reportWrongNumber` supersedes the active cycle — so
      // Stage 9 → "the number is wrong" → app killed → relaunch lands here with no cycle.
      _applyConflict(conflict, duringLoad: true);
    } on ProfileAlreadyCompleteException {
      if (!mounted) return;
      context.go('/ended');
    } on ProfileNotFoundException {
      if (!mounted) return;
      context.go('/');
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() {
        _view = _Stage9View.unavailable;
        _errorMessage = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _view = _Stage9View.unavailable;
        _errorMessage = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.';
      });
    }
  }

  /// Only the kinds the payload names. Every absent case — a passport's missing back, a purged
  /// body, an unknown kind — is one indistinguishable `404` by design, so probing for kinds the
  /// backend did not list would tell us nothing and cost a request each.
  Future<void> _loadImages(ScanDisplay display) async {
    final repository = ref.read(identityScanRepositoryProvider);
    for (final kind in display.availableImageKinds) {
      try {
        final bytes = await repository.reviewImage(kind);
        if (!mounted) return;
        setState(() => _images[kind] = bytes);
      } catch (_) {
        // A missing image must not take down a screen whose real subject is the national number.
        // The section simply does not appear.
      }
    }
  }

  /// Retries the Civil Registry lookup from the pause screen. This one DOES write — it is the only
  /// call on this screen that does anything but read.
  Future<void> _retryLookup() async {
    setState(() {
      _acting = true;
      _errorMessage = null;
    });
    try {
      final display = await ref.read(identityScanRepositoryProvider).retryRegistryLookup();
      if (!mounted) return;
      setState(() {
        _display = display;
        _view = display.registryReady ? _Stage9View.review : _Stage9View.paused;
      });
      if (display.registryReady) unawaited(_loadImages(display));
    } on ScanConflictException catch (conflict) {
      if (!mounted) return;
      _applyConflict(conflict);
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => _errorMessage = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.');
    } catch (_) {
      if (!mounted) return;
      setState(() => _errorMessage = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => _acting = false);
    }
  }

  Future<void> _accept() async {
    await _runAction(() async {
      await ref.read(identityScanRepositoryProvider).acceptReview();
      await ref
          .read(identityScanRepositoryProvider)
          .advanceToStage(resumeStageBeyondStage9);
      if (!mounted) return;
      // S5-08: the gate, not the old post-Stage-9 placeholder. Stages 10-12 exist now, and this
      // must go through the SAME door a relaunch goes through — `FinalStagesGateScreen` asks
      // `POST /submission/current` and lands the customer where the backend says. Sending them
      // straight to `/stage-10` would be this screen inferring a stage the backend owns, and
      // sending them to `/session-pending` (as this did until S5-08) left the whole of stages
      // 10-12 reachable only by killing and relaunching the app.
      context.go('/final-stages');
    });
  }

  /// customer.md: framed as a scanning problem, "because that is what it is: the wrong number was
  /// read". **Counts against the Stage 8 retry budget**, and the returned deadline is non-null
  /// only when this action's own attempt exhausted it.
  Future<void> _wrongNumber() async {
    await _runAction(() async {
      final blockedUntil = await ref.read(identityScanRepositoryProvider).reportWrongNumber();
      if (!mounted) return;
      if (blockedUntil != null) {
        setState(() {
          _view = _Stage9View.blocked;
          _blockedUntil = blockedUntil;
        });
        return;
      }
      // The backend has just superseded the cycle, so the device's `stage9` pointer now names a
      // stage whose state cannot exist — move it forward with the customer.
      await _goToScan();
    });
  }

  /// customer.md: terminal, and "the terminal message must explain rather than refuse ... 'Go to
  /// the branch' without a reason reads as the app failing."
  Future<void> _wrongDetails() async {
    await _runAction(() async {
      await ref.read(identityScanRepositoryProvider).reportWrongDetails();
      if (!mounted) return;
      _goToTerminal(
        'لا يمكن للبنك تسجيل بيانات هوية تختلف عمّا هو مسجّل في السجل المدني، ولا يمكن تصحيح '
        'السجل المدني من خلال هذا التطبيق. يرجى زيارة أي فرع ومعك وثيقة الهوية لتصحيح بياناتك.',
      );
    });
  }

  Future<void> _runAction(Future<void> Function() action) async {
    setState(() {
      _acting = true;
      _errorMessage = null;
    });
    try {
      await action();
    } on ScanConflictException catch (conflict) {
      if (!mounted) return;
      _applyConflict(conflict);
    } on ProfileAlreadyCompleteException {
      if (!mounted) return;
      context.go('/ended');
    } on ProfileNotFoundException {
      if (!mounted) return;
      context.go('/');
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() => _errorMessage = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.');
    } catch (_) {
      if (!mounted) return;
      setState(() => _errorMessage = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.');
    } finally {
      if (mounted) setState(() => _acting = false);
    }
  }

  /// Stage 9's conflict arms. There are four, not one — BL-042/BL-043 (S5-12) widened
  /// `wrong-number` to answer `SCAN_BLOCKED` and `REGISTRY_PENDING`, which it could not before, and
  /// gave all three actions the `PROFILE_TERMINAL` guard their siblings already had.
  /// [duringLoad] marks a conflict raised BY the loader rather than by one of the three actions.
  /// It matters for exactly one arm: the re-sync below re-reads through `_load`, so re-syncing a
  /// conflict that `_load` itself produced would loop forever — an unresolving spinner hammering
  /// `POST /registry-review/current`. When the read is the thing that refused, the disagreement is
  /// further back than Stage 9 and the customer belongs at the scan.
  void _applyConflict(ScanConflictException conflict, {bool duringLoad = false}) {
    switch (conflict.code) {
      case ScanConflictCode.profileTerminal:
        context.go('/ended');
      case ScanConflictCode.scanBlocked:
        setState(() {
          _view = _Stage9View.blocked;
          _blockedUntil = conflict.blockedUntil;
        });
      case ScanConflictCode.registryPending:
      case ScanConflictCode.registryNotReady:
        // The registry result is not there yet. Both codes mean the same thing to this screen —
        // they stay distinct on the wire because they are raised from different halves of the
        // journey, not because the customer should see two different pauses.
        setState(() => _view = _Stage9View.paused);
      case ScanConflictCode.stateConflict:
      case ScanConflictCode.unknown:
        // Re-read the authoritative state rather than guess. This is the re-sync `STATE_CONFLICT`
        // advises, and after an ACTION it is directly performable. After a failed load it is not —
        // see [duringLoad].
        if (duringLoad) {
          _goToScan();
        } else {
          unawaited(_load());
        }
      case ScanConflictCode.scanTypeExhausted:
      case ScanConflictCode.artifactExpired:
      case ScanConflictCode.imagesUnavailable:
        // Scan-stage codes. They should not reach Stage 9's actions, and if they do, the customer
        // belongs back at the scan rather than looking at a review that no longer holds.
        unawaited(_goToScan());
    }
  }

  /// Only the Civil-Registry mismatch message uses this now (BL-123). The three
  /// already-complete callers went to `/ended`, which owns its copy; this one stays because
  /// customer.md requires the mismatch terminal to EXPLAIN, and that explanation is true and
  /// specific to this one path.
  void _goToTerminal(String message) => context.go('/terminal', extra: message);

  /// Back to the scan, moving the resume pointer with the customer.
  ///
  /// Every path that reaches here has had its Stage 9 state invalidated server-side — a superseded
  /// cycle after "the number is wrong", or a read that found no cycle at all. Leaving the pointer
  /// on `stage9` would make a relaunch land on a stage whose state cannot exist, which is the
  /// concrete route into the loop [_applyConflict]'s `duringLoad` guard now bounds. This is a
  /// forward correction after a backend-side change, not the back-navigation case Stage 8's
  /// "change document" deliberately exempts.
  Future<void> _goToScan() async {
    await ref
        .read(identityScanRepositoryProvider)
        .advanceToStage(IdentityScanStage.stage8.name);
    if (!mounted) return;
    context.go('/stage-8');
  }

  // ---- rendering -------------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // Walk comment 10a. NOTE: this rename also removes «السجل المدني» from the title, which
      // is one of the two places the data source is named. Comment 10b — deleting the SECTION
      // label at `_registrySection` — is a disclosure-policy question the product owner has
      // not decided, and that label is deliberately left in place.
      appBar: BrandBanner(title: const ScreenTitle('مراجعة نتائج المسح')),
      // Walk comment 9 (walk of 2026-09-10). NOTE: «Walk comment 10a» a few lines above is from
      // a DIFFERENT, earlier walk — the numbering restarts per walk, which is why these carry
      // dates. This screen's actions are laid out INSIDE the body, whose view switches between
      // blocked, paused, error and review blocks, so there is no single action row to hold
      // clear. The whole body takes the inset instead. `top: false`: the app bar has it.
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            const JourneyProgress(step: JourneyStep.registryReview),
            if (widget.offline) const OfflineBanner(),
            Expanded(child: _body(context)),
          ],
        ),
      ),
    );
  }

  Widget _body(BuildContext context) {
    switch (_view) {
      case _Stage9View.loading:
        return const Center(child: CircularProgressIndicator());
      case _Stage9View.blocked:
        return BlockedView(
          blockedUntil: _blockedUntil,
          onRecheck: () => context.go('/stage-8'),
          onLeave: () => context.go('/'),
        );
      case _Stage9View.paused:
        return _paused(context);
      case _Stage9View.unavailable:
        return _centred(
          context,
          title: 'تعذر عرض البيانات',
          body: _errorMessage ?? 'يرجى المحاولة مرة أخرى لاحقًا.',
          primaryLabel: 'إعادة المحاولة',
          onPrimary: _load,
        );
      case _Stage9View.review:
        return _review(context, _display!);
    }
  }

  /// customer.md: "The customer sees a distinct state — the service is unavailable, progress is
  /// intact, come back shortly — not an error and not a failure." Deliberately not styled as an
  /// error, and deliberately not offering the three review actions, which the backend would refuse
  /// with `REGISTRY_NOT_READY` anyway.
  Widget _paused(BuildContext context) {
    return _centred(
      context,
      title: 'الخدمة غير متاحة مؤقتًا',
      body: 'تم مسح وثيقتك والتحقق منها بنجاح، ولم يتبقَّ سوى استكمال الاستعلام من السجل المدني. '
          'كل ما أنجزته محفوظ. يرجى المحاولة بعد قليل — لن تحتاج إلى إعادة المسح.',
      primaryLabel: 'إعادة المحاولة',
      onPrimary: _retryLookup,
    );
  }

  Widget _review(BuildContext context, ScanDisplay display) {
    final theme = Theme.of(context);
    return Column(
      children: [
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // customer.md: "The national number is shown first, alone and prominent, separated
                // from everything else. The entire screen hinges on the customer checking that one
                // value against the document in their hand, so it must not sit buried in a list of
                // fields."
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Text('الرقم الوطني', textAlign: TextAlign.center),
                        const SizedBox(height: 12),
                        // Forced LTR: the number is a digit string, and letting it inherit the
                        // page's RTL direction reorders how it reads against the document.
                        // `LtrValue` gives it its own bidi paragraph, which isolates it without
                        // inserting format characters into the value itself.
                        LtrValue(
                          display.nationalNumber,
                          textAlign: TextAlign.center,
                          style: theme.textTheme.headlineSmall,
                        ),
                        const SizedBox(height: 12),
                        const Text(
                          'قارن هذا الرقم بالرقم المدوّن في وثيقتك قبل المتابعة.',
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                // **Walk comment 10b — the data-source label is gone**, a product-owner decision
                // taken 2026-09-08: the customer is not told which authority supplied the data
                // they are confirming. This deletes the «بيانات السجل المدني» heading that stood
                // here. Note 10a had already removed the same phrase from the screen TITLE, so
                // this is the second of the two places it appeared as a LABEL.
                //
                // Two related strings were considered and handled differently, because "remove
                // the provenance label" is not "scrub the registry from the screen":
                //  * `_imageLabel`'s registry portrait is reworded — it named the source purely
                //    to tell two photographs apart, which a neutral label does just as well.
                //  * the mismatch guidance ABOVE (≈:211) still names the registry and MUST.
                //    It tells the customer their remedy is a branch visit with their document;
                //    stripped of what cannot be corrected here, that sentence stops being
                //    actionable. 10b removes a label, not an instruction.
                //
                // **Walk comment 10c** — with the heading gone, these fields would have been a
                // loose run on the bare canvas. A section is now expressed the way every other
                // grouping in this app expresses one: a white container on the tinted canvas,
                // exactly like the national-number card above. The emphasis 10c asked for is
                // structural rather than another heading style.
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        // Every one of these is nullable and absent rows are simply not rendered —
                        // a null is normal data here, not a defect to surface.
                        _field('الاسم', _arabicFullName(display)),
                        _field('اسم الأم', _motherFullName(display)),
                        // Both are Latin-reading values inside an RTL page: the English name is a
                        // wholly Latin run, and the date is a digit/slash run. Without their own
                        // direction they inherit the page's RTL and can be shown reordered.
                        _field('الاسم بالإنجليزية', _englishName(display), ltr: true),
                        _field('النوع', _sexLabel(display.sexRegistry)),
                        _field('تاريخ الميلاد', formatIsoDate(display.dateOfBirth), ltr: true),
                        _field('العنوان', display.rawAddressAr),
                      ],
                    ),
                  ),
                ),
                if (_images.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // «الصور» stays: it labels what the section contains, not where the
                          // data came from, so 10b does not reach it.
                          Text('الصور', style: theme.textTheme.titleSmall),
                          const SizedBox(height: 8),
                          for (final kind in display.availableImageKinds)
                            if (_images[kind] != null) ...[
                              Text(_imageLabel(kind)),
                              const SizedBox(height: 4),
                              Image.memory(
                                _images[kind]!,
                                fit: BoxFit.contain,
                                // D6.3/D9.4: cap the decode of an identity-document image.
                                cacheWidth: displayDecodeWidth(context),
                              ),
                              const SizedBox(height: 12),
                            ],
                        ],
                      ),
                    ),
                  ),
                ],
                if (_errorMessage != null) ...[
                  const SizedBox(height: 16),
                  Text(_errorMessage!, style: TextStyle(color: theme.colorScheme.error)),
                ],
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton(
                onPressed: _acting ? null : _accept,
                child: _acting
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('البيانات صحيحة، متابعة'),
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                onPressed: _acting ? null : _wrongNumber,
                child: const Text('الرقم الوطني غير صحيح'),
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                onPressed: _acting ? null : _wrongDetails,
                child: const Text('الرقم صحيح لكن بياناتي غير صحيحة'),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Label ABOVE the value, not beside it. The previous fixed 140 px label column produced three
  /// separate defects at once: a Latin-beginning value (the English name) sat at the far left of
  /// its own cell while its Arabic label sat at the right, the long registry address wrapped into
  /// a cramped two-line block, and at a 1.3x text scale the fixed column overflowed. Stacking
  /// removes all three and needs no width guess.
  ///
  /// [ltr] marks a value that reads left-to-right — a Latin name, a date. It gets [LtrValue], so
  /// the value becomes its own bidi paragraph instead of inheriting the page's RTL direction.
  Widget _field(String label, String? value, {bool ltr = false}) {
    if (value == null || value.trim().isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 2),
          if (ltr) LtrValue(value) else Text(value),
        ],
      ),
    );
  }

  Widget _centred(
    BuildContext context, {
    required String title,
    required String body,
    required String primaryLabel,
    required VoidCallback onPrimary,
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
          FilledButton(
            onPressed: _acting ? null : onPrimary,
            child: _acting
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(primaryLabel),
          ),
        ],
      ),
    );
  }

  static String? _joinNames(List<String?> parts) {
    final present = parts.where((p) => p != null && p.trim().isNotEmpty).map((p) => p!.trim());
    return present.isEmpty ? null : present.join(' ');
  }

  static String? _arabicFullName(ScanDisplay d) => _joinNames([
    d.nameArGiven,
    d.nameArFather,
    d.nameArGrandfather,
    d.nameArGreatGrandfather,
  ]);

  static String? _motherFullName(ScanDisplay d) => _joinNames([
    d.nameArMother,
    d.nameArMotherFather,
    d.nameArMotherGrandfather,
    d.nameArMotherGreatGrandfather,
  ]);

  static String? _englishName(ScanDisplay d) => _joinNames([d.firstNamesEn, d.lastNameEn]);

  /// The registry's own `m`/`f` (normalised backend-side by `RegistryFieldNormaliser`), or null.
  static String? _sexLabel(String? value) {
    switch (value) {
      case 'm':
        return 'ذكر';
      case 'f':
        return 'أنثى';
      default:
        return null;
    }
  }

  static String _imageLabel(String kind) {
    switch (kind) {
      case ScanImageKinds.docFront:
        return 'الوثيقة — الوجه الأمامي';
      case ScanImageKinds.docBack:
        return 'الوثيقة — الوجه الخلفي';
      case ScanImageKinds.portraitUqudo:
        return 'الصورة المستخرجة من الوثيقة';
      case ScanImageKinds.portraitRegistry:
        // Walk comment 10b. This named the supplying authority only to tell the two portraits
        // apart; «المسجّلة» (the one on record) draws the same distinction from «المستخرجة من
        // الوثيقة» (the one lifted off the document) without disclosing the source.
        return 'الصورة المسجّلة';
      default:
        return kind;
    }
  }
}
