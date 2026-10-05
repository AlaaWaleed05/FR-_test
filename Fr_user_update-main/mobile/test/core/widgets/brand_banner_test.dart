import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/theme/app_theme.dart';
import 'package:mobile/core/widgets/brand_banner.dart';
import 'package:mobile/core/widgets/brand_dune.dart';

/// `Image.asset(..., cacheWidth: ...)` wraps its provider in a `ResizeImage`, so reaching for
/// `AssetImage` directly finds nothing. Unwrap once here.
String? _assetNameOf(Image image) {
  final provider = image.image;
  if (provider is ResizeImage) {
    final inner = provider.imageProvider;
    return inner is AssetImage ? inner.assetName : null;
  }
  return provider is AssetImage ? provider.assetName : null;
}

Future<void> _pump(
  WidgetTester tester,
  PreferredSizeWidget banner, {
  TextDirection direction = TextDirection.rtl,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      home: Directionality(
        textDirection: direction,
        child: Scaffold(appBar: banner, body: const SizedBox()),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('BrandBanner — AD-012', () {
    testWidgets('carries the pearl mark and the screen title', (tester) async {
      await _pump(tester, const BrandBanner(title: Text('عنوان السكن')));

      final assets = tester.widgetList<Image>(find.byType(Image)).map(_assetNameOf);
      expect(assets, contains('assets/brand/sfb-pearl.png'));
      expect(find.text('عنوان السكن'), findsOneWidget);
    });

    /// The documented adaptation, asserted so a later "restore the handoff exactly" pass has to
    /// read the reasoning first: the wordmark is DROPPED from inner screens because the screen
    /// title takes its width, and the splash is where the identity moment lives.
    testWidgets('does NOT repeat the bank wordmark on inner screens', (tester) async {
      await _pump(tester, const BrandBanner(title: Text('عنوان السكن')));

      final assets = tester.widgetList<Image>(find.byType(Image)).map(_assetNameOf);
      expect(assets, isNot(contains('assets/brand/sfb-wordmark-white.png')));
      expect(assets, isNot(contains('assets/brand/sfb-wordmark-navy.png')));
    });

    /// This app has no customer authentication and no session to end, so the handoff's sign-out
    /// control would do nothing. A button that signs nobody out is the same class of untrue
    /// affordance BL-123 and BL-106 exist to remove.
    testWidgets('has no sign-out control', (tester) async {
      await _pump(tester, const BrandBanner(title: Text('عنوان السكن')));

      expect(find.byIcon(Icons.logout), findsNothing);
      expect(find.byIcon(Icons.logout_outlined), findsNothing);
    });

    testWidgets('is navy, and taller than a stock bar by the dune allowance', (tester) async {
      const banner = BrandBanner(title: Text('عنوان السكن'));
      // The curve is cut into the bottom of the bar, so content at the stock baseline would
      // collide with it. The extra height IS the handoff's enlarged bottom padding.
      expect(banner.preferredSize.height, greaterThan(kToolbarHeight));

      await _pump(tester, banner);
      final ground = tester.widget<ColoredBox>(find.byType(ColoredBox).first);
      expect(ground.color, AppTheme.brandNavy);
      expect(find.byType(BrandDune), findsOneWidget);
    });

    /// The dune fills with the colour BELOW the bar. AD-012 fork 3 keeps the tinted canvas, so a
    /// hardcoded white would leave a visible sliver between the curve and the page.
    testWidgets('the dune takes its fill from the scaffold, not from white', (tester) async {
      await _pump(tester, const BrandBanner(title: Text('عنوان السكن')));

      final canvas = AppTheme.light().scaffoldBackgroundColor;
      expect(canvas, isNot(Colors.white), reason: 'the premise: the canvas is tinted');
      // Rendered without exception against a tinted canvas is the reachable assertion here; the
      // colour itself is passed straight through to the painter.
      expect(find.byType(BrandDune), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    /// **Regression test for a BLOCKER this session introduced.** The bar's ground is
    /// `AppTheme.brandNavy`, which AD-012 also pins as `colorScheme.primary` — and a bare
    /// `TextButton` takes its foreground from `primary`. So the abandon-session action on
    /// Stage 1b and Stage 2 rendered navy-on-navy at 1:1 contrast: present, correctly labelled,
    /// tappable, and invisible. Found by `@agent-reviewer`; Flutter documents the same trap in
    /// `AppBar`'s own "Why don't my TextButton actions appear?" note.
    testWidgets('a TextButton action is legible, not navy-on-navy', (tester) async {
      await _pump(
        tester,
        BrandBanner(
          title: const Text('التحقق من قنوات الاتصال'),
          actions: [TextButton(onPressed: () {}, child: const Text('إلغاء'))],
        ),
      );

      final label = tester.widget<Text>(find.text('إلغاء'));
      final resolved =
          label.style?.color ??
          DefaultTextStyle.of(tester.element(find.text('إلغاء'))).style.color;
      expect(
        resolved,
        isNot(AppTheme.brandNavy),
        reason: 'an action the same colour as the bar it sits on cannot be seen',
      );
      expect(resolved, Colors.white);
    });

    /// The other half of the same layout defect: the mark takes 44 dp out of the title slot, and
    /// `ScreenTitle`'s `FittedBox(scaleDown)` shrinks without a floor — so with an action present
    /// the title collapsed to a sub-pixel sliver and RENDERED rather than overflowing, which is
    /// exactly why no existing test noticed.
    testWidgets('the title keeps a usable box when an action is present', (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      await _pump(
        tester,
        BrandBanner(
          title: const Text('التحقق من قنوات الاتصال'),
          actions: [TextButton(onPressed: () {}, child: const Text('إلغاء'))],
        ),
      );

      final titleBox = tester.getSize(find.text('التحقق من قنوات الاتصال'));
      expect(
        titleBox.width,
        greaterThan(80),
        reason: 'measured 5.1 px before the actions were width-capped',
      );
      expect(titleBox.height, greaterThan(10));
    });

    group('markOnly', () {
      testWidgets('shows the mark and no title', (tester) async {
        await _pump(tester, const BrandBanner.markOnly());

        final assets = tester.widgetList<Image>(find.byType(Image)).map(_assetNameOf);
        expect(assets, contains('assets/brand/sfb-pearl.png'));
        expect(find.byType(Text), findsNothing);
      });

      testWidgets('offers no way back — these screens are terminal', (tester) async {
        const banner = BrandBanner.markOnly();
        expect(banner.automaticallyImplyLeading, isFalse);

        await _pump(tester, banner);
        expect(find.byType(BackButton), findsNothing);
      });
    });

    /// The handoff says the header and its dune mirror under RTL. This app is Arabic-first, so
    /// the mirror is the ordinary case — but neither side may be hardcoded, or a widget test
    /// pumping LTR would render a broken composition.
    testWidgets('renders in both directions without hardcoding a side', (tester) async {
      for (final direction in TextDirection.values) {
        await _pump(
          tester,
          const BrandBanner(title: Text('عنوان السكن')),
          direction: direction,
        );
        expect(find.byType(BrandDune), findsOneWidget, reason: direction.name);
        expect(tester.takeException(), isNull, reason: direction.name);
      }
    });
  });
}
