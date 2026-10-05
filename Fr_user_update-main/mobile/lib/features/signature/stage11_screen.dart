import 'dart:async' show unawaited;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:signature/signature.dart' as sig;

import '../../core/entry/entry_models.dart' show BackendUnreachableException;
import '../../core/images/decode_width.dart';
import '../../core/images/image_downscale.dart';
import '../../core/journey/journey_error.dart';
import '../../core/signature/signature_models.dart';
import '../../core/signature/signature_providers.dart';
import '../entry/offline_banner.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/journey_progress.dart';

/// Journey Stage 11 — signature (docs/journeys/customer.md).
///
/// **Two routes, neither preferred.** customer.md: "Draw on screen — a signature pad, with a
/// clear-and-retry control. Upload an image — from the device's photo library or camera ... Neither
/// is preferred; the customer chooses."
///
/// **Mandatory, with no skip.** "A customer who can neither draw nor upload cannot complete the
/// journey and must go to a branch. This is a deliberate consequence of the requirement, not an
/// oversight." Back is disabled: liveness is complete and its artifact issued, so there is nothing
/// to return to.
///
/// **Size handling matches Stage 6's salary certificate, deliberately.** A signature can be a
/// photograph, which makes it exactly as large as any other phone photograph, so it gets the same
/// treatment: the customer may pick anything up to [maxPickedImageBytes] (10 MB), and what actually
/// goes on the wire is whatever [downscaleToJpeg] makes of it — a 1600 px JPEG, typically a few
/// hundred KB.
///
/// That matters for more than tidiness. `SignatureService` refuses over 5 MB with a coded
/// `SIGNATURE_REJECTED`, but `SignatureController`'s separate base64-length cap answers a BARE,
/// uncoded 400 above roughly 5.72 MB — so a customer uploading a large photo of their signature
/// could otherwise be shown "bad request" for a file that was merely big. Downscaling before
/// encoding puts that band out of reach entirely. No narrower client-side rule is invented, and no
/// customer is ever asked to sign more simply.
class Stage11Screen extends ConsumerStatefulWidget {
  const Stage11Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage11Screen> createState() => _Stage11ScreenState();
}

class _Stage11ScreenState extends ConsumerState<Stage11Screen> {
  /// `exportBackgroundColor` is white rather than transparent: the stored artifact is looked at by
  /// an operator, and a transparent PNG renders as black-on-black in some viewers.
  final _pad = sig.SignatureController(
    penStrokeWidth: 3,
    penColor: Colors.black,
    exportBackgroundColor: Colors.white,
  );

  /// The uploaded-route capture, held until submit. Null while the draw route is in use.
  CapturedSignature? _uploaded;

  /// Which of the two routes the customer has chosen (walk comment 12a); null until they choose.
  ///
  /// Explicit state rather than deriving it from `_uploaded != null`: the customer picks "upload"
  /// BEFORE they have picked an image, and a derived flag would snap the screen back to the
  /// drawing pad in the moment between choosing the route and choosing the file.
  ///
  /// **Defaults to the drawing route, and that is deliberately BEHAVIOUR-PRESERVING.**
  /// customer.md Stage 11 says *"Neither is preferred; the customer chooses"*, and an empty
  /// initial selection would honour that sentence most literally — but it would also make
  /// drawing cost one tap where it previously cost none, on a mandated journey for a
  /// mixed-literacy audience. The pre-existing screen showed the pad immediately and made upload
  /// one tap away; this keeps exactly that balance while making the two routes VISIBLE as two
  /// routes, which is all walk comment 12a asked for. Neither route is made harder than it was.
  bool _uploadMode = false;

  bool _busy = false;
  String? _error;

  /// The pad's on-screen size, captured by the `LayoutBuilder` so the PNG export can be bounded by
  /// the same long-edge rule the upload route uses.
  Size? _padSize;

  @override
  void initState() {
    super.initState();
    _pad.addListener(_onPadChanged);
  }

  @override
  void dispose() {
    _pad.removeListener(_onPadChanged);
    _pad.dispose();
    super.dispose();
  }

  /// Tracks only whether the pad has ANY stroke — not its contents.
  bool _padHasInk = false;

  /// Rebuilds only when emptiness actually flips, never per stroke point.
  ///
  /// The controller notifies on every point, so a bare `setState` here schedules a frame for each
  /// one — enough to keep a widget test's `pumpAndSettle` from ever settling, and enough to rebuild
  /// the whole form while a customer is mid-signature on a low-end handset. The only thing the
  /// build actually depends on is whether the Next button is enabled.
  void _onPadChanged() {
    final hasInk = _pad.isNotEmpty;
    if (hasInk == _padHasInk) return;
    if (mounted) setState(() => _padHasInk = hasInk);
  }

  bool get _hasCapture => _uploaded != null || _padHasInk;

  Future<void> _pick(ImageSource source) async {
    setState(() => _error = null);
    final Uint8List bytes;
    try {
      final picked = await ImagePicker().pickImage(source: source);
      if (picked == null) return;
      bytes = await picked.readAsBytes();
    } on PlatformException catch (_) {
      if (!mounted) return;
      setState(
        () => _error = 'تعذر فتح الكاميرا أو المعرض. تأكد من السماح للتطبيق بالوصول إليهما من إعدادات الهاتف.',
      );
      return;
    }

    // Stage 6's rules exactly, in one testable place — see `signatureFromPickedBytes`.
    final result = signatureFromPickedBytes(bytes);
    if (!mounted) return;
    final failure = result.failure;
    if (failure != null) {
      setState(
        () => _error = switch (failure) {
          SignaturePickFailure.tooLarge =>
            'حجم الملف يتجاوز الحد الأقصى (10 ميغابايت).',
          SignaturePickFailure.unreadable => 'تعذر قراءة الصورة المحددة.',
        },
      );
      return;
    }
    setState(() {
      // The two routes are exclusive: an upload replaces anything drawn, and vice versa.
      _pad.clear();
      _padHasInk = false;
      _uploaded = result.capture;
    });
  }

  void _clear() {
    setState(() {
      _pad.clear();
      _padHasInk = false;
      _uploaded = null;
      _error = null;
    });
  }

  /// Exports the drawn pad as PNG, bounded by the same long-edge rule the upload route uses.
  ///
  /// Line art at 1600 px encodes to tens of KB, so this is nowhere near any backend limit — the
  /// bound exists so that a future device with an unusually large canvas cannot drift toward one,
  /// not because a signature is expected to approach it.
  Future<CapturedSignature?> _exportDrawn() async {
    final size = _padSize;
    final target = size == null
        ? null
        : imageResizeTarget(size.width.round(), size.height.round());
    final png = await _pad.toPngBytes(
      width: target?.width,
      height: target?.height,
    );
    if (png == null) return null;
    return CapturedSignature(method: SignatureCaptureMethod.drawn, bytes: png);
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final capture = _uploaded ?? await _exportDrawn();
      if (capture == null) {
        if (!mounted) return;
        setState(() {
          _busy = false;
          _error = 'تعذر حفظ التوقيع. يرجى المحاولة مرة أخرى.';
        });
        return;
      }
      // The genuine last resort, and it should never fire: both routes downscale first, so what
      // reaches here is a ≤1600 px image on the order of a few hundred KB. It exists so a future
      // path that skips the downscale fails HERE, with copy a customer can act on, rather than as
      // the backend's bare uncoded 400 above ~5.72 MB (BL-060).
      if (capture.bytes.length > backendSignatureMaxBytes) {
        if (!mounted) return;
        setState(() {
          _busy = false;
          _error = 'حجم التوقيع كبير جدًا. يرجى إعادة التوقيع أو اختيار صورة أخرى.';
        });
        return;
      }
      await ref.read(signatureRepositoryProvider).submit(capture);
      if (!mounted) return;
      context.go('/stage-12', extra: widget.offline);
    } on SignatureRejectedException {
      // The one place a size or format problem can still surface. Honest and generic, because the
      // backend gives one code for all four of its causes — it does not tell us which.
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error =
            'تعذر قبول هذا التوقيع. يرجى إعادة التوقيع أو اختيار صورة أخرى.';
      });
    } on JourneyConflictException catch (e) {
      if (!mounted) return;
      _applyConflict(e);
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.';
      });
    } on Object {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى.';
      });
    }
  }

  void _applyConflict(JourneyConflictException conflict) {
    switch (conflict.code) {
      case JourneyCode.livenessRequired:
        // Stage 10 has not passed. The backend, not this screen, is the authority on that.
        context.go('/stage-10', extra: widget.offline);
      case JourneyCode.profileTerminal:
        context.go('/ended');
      default:
        context.go('/final-stages', extra: widget.offline);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // No `leading` and no back affordance: customer.md makes Back disabled at this stage.
      appBar: BrandBanner(
        title: const Text('التوقيع'),
        automaticallyImplyLeading: false,
      ),
      body: SafeArea(
        child: Column(
          children: [
            const JourneyProgress(step: JourneyStep.signature),
            if (widget.offline) const OfflineBanner(),
            Expanded(
              child: _busy
                  ? const Center(child: CircularProgressIndicator())
                  : _form(),
            ),
          ],
        ),
      ),
    );
  }

  /// Walk comment 12a: «the two options should be evident as two options». They were not — the
  /// drawing pad simply appeared, and the two upload buttons sat underneath it as though they were
  /// extra actions ON the pad rather than an alternative to it. A customer who wanted to attach a
  /// photograph had no way to see that as a route.
  Widget _routeChooser() {
    return SegmentedButton<bool>(
      segments: const [
        ButtonSegment(
          value: false,
          icon: Icon(Icons.draw_outlined),
          label: Text('التوقيع على الشاشة'),
        ),
        ButtonSegment(
          value: true,
          icon: Icon(Icons.image_outlined),
          label: Text('رفع صورة'),
        ),
      ],
      selected: {_uploadMode},
      showSelectedIcon: false,
      onSelectionChanged: (selection) {
        if (selection.isEmpty) return;
        final wantsUpload = selection.first;
        if (wantsUpload == _uploadMode) return;
        setState(() {
          _uploadMode = wantsUpload;
          // The two routes are exclusive, which the existing `_pick`/`_clear` pair already
          // enforces; switching route discards whatever the abandoned one held so the customer
          // cannot submit something they can no longer see.
          _clear();
        });
      },
    );
  }

  Widget _uploadButtons() {
    return Row(
      children: [
        Expanded(
          child: OutlinedButton.icon(
            onPressed: () => _pick(ImageSource.gallery),
            icon: const Icon(Icons.photo_library_outlined),
            label: const Text('اختيار صورة'),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: OutlinedButton.icon(
            onPressed: () => _pick(ImageSource.camera),
            icon: const Icon(Icons.camera_alt_outlined),
            label: const Text('التقاط صورة'),
          ),
        ),
      ],
    );
  }

  Widget _form() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('يرجى التوقيع للإقرار بصحة البيانات التي أدخلتها.'),
          const SizedBox(height: 16),
          _routeChooser(),
          const SizedBox(height: 16),
          if (_uploadMode) ...[
            if (_uploaded != null) ...[
            Text('صورة التوقيع', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            // BL-076: this was a FIXED `height: 180` — a landscape band. A signature photographed
            // in portrait (which is how a phone is held) letterboxed into a narrow strip with
            // most of the box empty, so the customer could not actually check what they had
            // attached. A max-height constraint instead of a fixed one lets a portrait capture
            // use the room it needs while a landscape one is unchanged.
            //
            // D6.3/D9.4: `cacheWidth` caps the decode. An uncapped full-resolution camera image
            // is tens of MB of ARGB on a 2 GB device — a house rule, not an optimisation.
            ColoredBox(
              color: Colors.white,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 320),
                child: Image.memory(
                  _uploaded!.bytes,
                  fit: BoxFit.contain,
                  cacheWidth: displayDecodeWidth(context),
                ),
              ),
            ),
            ],
            const SizedBox(height: 8),
            _uploadButtons(),
          ] else ...[
            Text(
              'التوقيع على الشاشة',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            LayoutBuilder(
              builder: (context, constraints) {
                _padSize = Size(constraints.maxWidth, 180);
                return DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.grey),
                  ),
                  child: sig.Signature(
                    controller: _pad,
                    height: 180,
                    backgroundColor: Colors.white,
                  ),
                );
              },
            ),
          ],
          const SizedBox(height: 8),
          TextButton(
            onPressed: _hasCapture ? _clear : null,
            child: const Text('مسح والإعادة'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _hasCapture ? () => unawaited(_submit()) : null,
            child: const Text('التالي'),
          ),
          const SizedBox(height: 16),
          // BL-115. The submit button is disabled until there is a capture, and customer.md
          // disables Back at this stage, so a customer who can neither draw nor photograph a
          // signature previously sat on a screen with no control they could use and no advice at
          // all. Nothing is spent here and nothing expires -- the harm was never a burnt account,
          // only a customer with no idea what to do next -- so the fix is the one sentence the
          // screen already owed them. Deliberately NOT a Back button: disabling Back at Stage 11
          // is customer.md's rule, not an oversight.
          //
          // The phrasing deliberately does NOT say the customer "can complete their request" at a
          // branch — found by `@agent-reviewer` on the S8-14 diff. Ruling A permits pointing to a
          // branch and forbids promising an outcome, and "you can complete it there" is that
          // promise without the word. The same softening was applied to `BlockedView`'s case 3.
          Text(
            'إذا تعذر عليك التوقيع على الشاشة أو رفع صورة، يرجى زيارة أقرب فرع.',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}
