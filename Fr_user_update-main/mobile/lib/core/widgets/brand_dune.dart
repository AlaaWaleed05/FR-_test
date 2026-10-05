import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Design_3's dune curve — the signature edge shared by the splash and the app banner (AD-012).
///
/// **Why a painter and not an image.** The curve spans the full screen width at two very
/// different heights (500 dp on the splash, 28 dp under the banner) and has to stretch
/// independently on each axis — the handoff's own SVG sets `preserveAspectRatio="none"` for
/// exactly that reason. A raster would band on the gradient and blur on the stroke at those
/// scales; the paths are eight cubic segments, so drawing them is cheaper than shipping them.
///
/// **The curve is the design.** The handoff records that a flat divider was explicitly rejected,
/// so this is not a decoration that can be simplified away to a straight edge.
///
/// **Mirrored under RTL.** The handoff says the dune mirrors with the header (`scaleX(-1)`), and
/// this app is Arabic-first, so the mirror is the normal case rather than the exception. Read
/// from the ambient [Directionality] rather than hard-coded, so a widget test that pumps LTR
/// still gets a coherent drawing.
///
/// Every path below is transcribed from `Design_3/design_handoff_sfb_app/designs/` at its
/// authored viewBox and scaled here; do not "tidy" the control points, they are the artwork.
class BrandDune extends StatelessWidget {
  /// The splash's dune: a navy sky occupying the upper area, cut by the curve, with the steel
  /// glow and two accent contours below it. Sized by its parent.
  const BrandDune.sky({super.key})
      : _variant = _DuneVariant.sky,
        _edgeColor = null;

  /// The banner's bottom edge: a 28 dp-tall shape filled with the colour BELOW the bar, so the
  /// curve reads as the bar being cut rather than as a band drawn on top of it.
  ///
  /// [edgeColor] must therefore be the surface the banner sits on — pass the scaffold's own
  /// background, never a guess at white.
  const BrandDune.bannerEdge({super.key, required Color edgeColor})
      : _variant = _DuneVariant.bannerEdge,
        _edgeColor = edgeColor;

  final _DuneVariant _variant;
  final Color? _edgeColor;

  @override
  Widget build(BuildContext context) {
    final mirror = Directionality.of(context) == TextDirection.rtl;
    return CustomPaint(
      painter: _DunePainter(
        variant: _variant,
        edgeColor: _edgeColor,
        mirror: mirror,
      ),
      // The painter fills whatever box it is given; both callers position it themselves.
      size: Size.infinite,
    );
  }
}

enum _DuneVariant { sky, bannerEdge }

class _DunePainter extends CustomPainter {
  const _DunePainter({
    required this.variant,
    required this.edgeColor,
    required this.mirror,
  });

  final _DuneVariant variant;
  final Color? edgeColor;
  final bool mirror;

  /// The authored viewBox widths/heights the paths below are expressed in.
  static const double _designWidth = 390;
  static const double _skyHeight = 500;
  static const double _edgeHeight = 28;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    canvas.save();
    if (mirror) {
      canvas
        ..translate(size.width, 0)
        ..scale(-1, 1);
    }
    // `preserveAspectRatio="none"`: scale the two axes independently so the curve stretches to
    // the box rather than letterboxing inside it.
    final designHeight = variant == _DuneVariant.sky ? _skyHeight : _edgeHeight;
    canvas.scale(size.width / _designWidth, size.height / designHeight);

    switch (variant) {
      case _DuneVariant.sky:
        _paintSky(canvas);
      case _DuneVariant.bannerEdge:
        _paintBannerEdge(canvas);
    }
    canvas.restore();
  }

  void _paintSky(Canvas canvas) {
    final sky = Path()
      ..moveTo(0, 0)
      ..lineTo(_designWidth, 0)
      ..lineTo(_designWidth, 372)
      ..cubicTo(312, 432, 250, 392, 195, 404)
      ..cubicTo(132, 418, 84, 462, 0, 420)
      ..close();

    canvas.drawPath(
      sky,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          // Flat navy for the top 62%, then a lift toward navy-deep — the gradient is what
          // stops a 500 dp field of one colour reading as a dead rectangle.
          colors: [AppTheme.brandNavy, AppTheme.brandNavy, Color(0xFF132A5E)],
          stops: [0, 0.62, 1],
        ).createShader(const Rect.fromLTWH(0, 0, _designWidth, _skyHeight)),
    );

    // The steel glow, centred low on the dune crest.
    canvas.drawPath(
      sky,
      Paint()
        ..shader = RadialGradient(
          center: const Alignment(0, 0.84), // 50% / 92% of the box
          radius: 0.62,
          colors: [
            AppTheme.brandSteel.withValues(alpha: 0.5),
            AppTheme.brandSteel.withValues(alpha: 0),
          ],
          stops: const [0.5, 1],
        ).createShader(const Rect.fromLTWH(0, 0, _designWidth, _skyHeight)),
    );

    _contour(canvas, from: 436, opacity: 0.45, width: 1.2, points: const [
      [88.0, 478.0, 134.0, 434.0, 197.0, 420.0],
      [252.0, 408.0, 314.0, 448.0, 390.0, 388.0],
    ]);
    _contour(canvas, from: 468, opacity: 0.22, width: 1, points: const [
      [96.0, 504.0, 140.0, 462.0, 203.0, 450.0],
      [258.0, 440.0, 318.0, 476.0, 390.0, 420.0],
    ]);
  }

  /// One open accent line across the sand below the crest.
  void _contour(
    Canvas canvas, {
    required double from,
    required double opacity,
    required double width,
    required List<List<double>> points,
  }) {
    final path = Path()..moveTo(0, from);
    for (final p in points) {
      path.cubicTo(p[0], p[1], p[2], p[3], p[4], p[5]);
    }
    canvas.drawPath(
      path,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = width
        ..color = AppTheme.brandSteel.withValues(alpha: opacity),
    );
  }

  void _paintBannerEdge(Canvas canvas) {
    // Filled with the colour BELOW the bar: this shape subtracts from the bar rather than
    // adding to the page, which is why it must be told the real surface colour.
    final fill = Path()
      ..moveTo(_designWidth, 2)
      ..cubicTo(312, 15.3, 250, 6.4, 195, 9.1)
      ..cubicTo(132, 12.2, 84, 22, 0, 12.7)
      ..lineTo(0, _edgeHeight)
      ..lineTo(_designWidth, _edgeHeight)
      ..close();
    canvas.drawPath(fill, Paint()..color = edgeColor!);

    // The same curve stroked, so the boundary reads as drawn rather than as a colour change.
    final stroke = Path()
      ..moveTo(_designWidth, 2)
      ..cubicTo(312, 15.3, 250, 6.4, 195, 9.1)
      ..cubicTo(132, 12.2, 84, 22, 0, 12.7);
    canvas.drawPath(
      stroke,
      Paint()
        ..style = PaintingStyle.stroke
        // The handoff specifies a NON-SCALING 1 pt stroke, and this is the honest version of
        // that: `paint()` has already scaled the canvas anisotropically, so the drawn line is
        // 1 unit thick in DESIGN space and lands slightly wider on X than on Y (about 1.05× on
        // a 411 dp frame). Dividing it back out per-axis is not possible with one strokeWidth.
        // At a hairline weight on a 28 dp edge the difference is sub-pixel, so it is accepted
        // rather than worked around — recorded because an earlier version of this comment
        // claimed a division that was never in the code (`@agent-reviewer`, S8-16).
        ..strokeWidth = 1
        ..color = AppTheme.brandSteel.withValues(alpha: 0.55),
    );
  }

  @override
  bool shouldRepaint(_DunePainter old) =>
      old.variant != variant || old.edgeColor != edgeColor || old.mirror != mirror;
}
