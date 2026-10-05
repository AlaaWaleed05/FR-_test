import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'brand_dune.dart';

/// Design_3's app header, on every screen that has one (AD-012).
///
/// **What the handoff gives and what it does not.** `Design_3/design_handoff_sfb_app/designs/SFB
/// Mobile Banner.dc.html` specifies four variants of one anatomy: pearl mark → Arabic + Latin
/// wordmark → flexible spacer → outlined sign-out button, on a `#0b1c47` bar, with a steel glow
/// behind the mark and the dune curve cut into the bottom edge. It specifies **no screen title**,
/// because the screen it was drawn against is a dashboard whose heading lives in the body.
///
/// **Every screen in this app has a title, and the title is load-bearing** — a twelve-stage
/// mandated journey where the customer needs to know which step they are on. So this is a
/// documented ADAPTATION, not a transcription:
///
/// - the **mark stays** at the leading edge, with its glow, its steel ring and the dune edge;
/// - the **wordmark is dropped from inner screens** and the screen title takes its place. The
///   bank's name is not absent from the app — the splash carries the wordmark at full size, which
///   is where the handoff itself puts the identity moment. Repeating it on all nineteen screens
///   would cost the width the title needs on a 390 dp frame and say nothing new;
/// - the **sign-out button is dropped entirely**. This app has no customer authentication and no
///   session to end, so the control would do nothing. A button that signs nobody out is the same
///   class of untrue affordance this session exists to remove.
///
/// **The bar is a hair taller than a stock `AppBar` and that is structural.** The dune curve is
/// cut into the bottom 28 dp, so content sitting at the normal baseline would collide with it.
/// The handoff solves this by enlarging the bar's bottom padding (26-34); [_duneHeight] is that
/// allowance, and the title is laid out above it.
///
/// **RTL.** The handoff says the header mirrors and the dune curve mirrors with it. This app is
/// Arabic-first, so that is the ordinary case — [BrandDune] reads the ambient [Directionality]
/// and the row below uses start/end rather than left/right, so neither hardcodes a side.
class BrandBanner extends StatelessWidget implements PreferredSizeWidget {
  const BrandBanner({
    super.key,
    required Widget this.title,
    this.actions,
    this.automaticallyImplyLeading = true,
  });

  /// The handoff's fourth variant — **mark only, no wordmark and no title**.
  ///
  /// For the terminal screens (`EndedScreen`, `TerminalScreen`, `SessionCompleteScreen`,
  /// `ApprovedScreen`/`RejectedScreen`), whose whole composition is one centred heading. Giving
  /// those a titled bar would print the heading twice — once in the bar and once six lines below
  /// it — so they get the brand without the repetition. They are terminal, so there is also
  /// nothing to go back to: [automaticallyImplyLeading] is forced false.
  const BrandBanner.markOnly({super.key})
      : title = null,
        actions = null,
        automaticallyImplyLeading = false;

  /// The screen's own title, or null for [BrandBanner.markOnly]. Kept as a `Widget` rather than
  /// a `String` because several screens pass `ScreenTitle`, which handles its own overflow.
  final Widget? title;

  final List<Widget>? actions;

  /// False on the terminal and post-submission screens, which are reached by `go` and have
  /// nothing behind them to return to.
  final bool automaticallyImplyLeading;

  /// The dune occupies the bottom of the bar, so the bar is that much taller than stock.
  static const double _duneHeight = 28;

  /// Stock toolbar height plus the dune allowance.
  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight + _duneHeight);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // The dune's fill must be the surface BELOW the bar — the curve subtracts from the bar
    // rather than being painted onto the page. Read from the scaffold rather than assumed white:
    // AD-012 fork 3 keeps the tinted canvas, so white would leave a visible sliver.
    final below = theme.scaffoldBackgroundColor;

    return Stack(
      children: [
        Positioned.fill(
          child: ColoredBox(
            color: AppTheme.brandNavy,
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: FractionallySizedBox(
                widthFactor: 0.8,
                heightFactor: 1,
                child: DecoratedBox(
                  // The handoff's glow, behind the mark: a wide steel wash that keeps a flat
                  // navy bar from reading as a dead rectangle.
                  decoration: BoxDecoration(
                    gradient: RadialGradient(
                      center: AlignmentDirectional.centerStart.resolve(
                        Directionality.of(context),
                      ),
                      radius: 1.2,
                      colors: [
                        AppTheme.brandSteel.withValues(alpha: 0.34),
                        AppTheme.brandSteel.withValues(alpha: 0),
                      ],
                      stops: const [0, 0.68],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
        // The curve, cut into the bottom edge.
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          height: _duneHeight,
          child: BrandDune.bannerEdge(edgeColor: below),
        ),
        // The bar's own content, held clear of the curve.
        Positioned(
          left: 0,
          right: 0,
          top: 0,
          bottom: _duneHeight,
          child: AppBar(
            // The Stack behind this is the whole visual treatment, so the AppBar itself paints
            // nothing — it is here for the leading button, the title layout and the semantics
            // that come with a real app bar rather than a hand-rolled row.
            backgroundColor: Colors.transparent,
            surfaceTintColor: Colors.transparent,
            elevation: 0,
            scrolledUnderElevation: 0,
            automaticallyImplyLeading: automaticallyImplyLeading,
            foregroundColor: Colors.white,
            titleSpacing: 8,
            title: Row(
              children: [
                _mark(context),
                if (title != null) ...[
                  const SizedBox(width: 10),
                  // The title takes the width the wordmark would have had. `Flexible` rather
                  // than `Expanded` so a short title does not stretch its hit area across the bar.
                  Flexible(
                    child: DefaultTextStyle.merge(
                      style: theme.textTheme.titleMedium?.copyWith(color: Colors.white),
                      overflow: TextOverflow.ellipsis,
                      child: title!,
                    ),
                  ),
                ],
              ],
            ),
            // **Actions must be told they are on navy.** A bare `TextButton` takes its
            // foreground from `colorScheme.primary`, which AD-012 pinned to the navy that is now
            // the BAR'S OWN GROUND — so the abandon-session action on Stage 1b and Stage 2
            // rendered navy-on-navy at 1:1 contrast, i.e. invisible, and no test noticed because
            // the widget was present and correctly labelled. Flutter documents this exact trap
            // in `AppBar`'s own "Why don't my TextButton actions appear?" note. Found by
            // `@agent-reviewer` on the S8-16 diff.
            actions: actions == null
                ? null
                : [
                    // **And capped in width.** The mark takes 44 dp out of the title slot, and
                    // `ScreenTitle`'s `FittedBox(scaleDown)` shrinks without a floor — so on a
                    // 360 dp frame with a text action present the title collapsed to a
                    // sub-pixel sliver and RENDERED rather than overflowing, which is why no
                    // test caught it. Capping the actions at 40% is what leaves the title a
                    // usable box. Also found by `@agent-reviewer`; the pre-existing layout was
                    // already marginal (walk bundle W-3) and the mark is what tipped it over.
                    ConstrainedBox(
                      constraints: BoxConstraints(
                        maxWidth: MediaQuery.sizeOf(context).width * 0.4,
                      ),
                      child: TextButtonTheme(
                        data: TextButtonThemeData(
                          style: TextButton.styleFrom(foregroundColor: Colors.white),
                        ),
                        child: Row(mainAxisSize: MainAxisSize.min, children: actions!),
                      ),
                    ),
                  ],
          ),
        ),
      ],
    );
  }

  /// The pearl, with the handoff's 1.2 px steel ring at the small-variant weight.
  Widget _mark(BuildContext context) {
    const size = 34.0;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        border: Border.all(
          color: AppTheme.brandSteel.withValues(alpha: 0.8),
          width: 1.2,
        ),
      ),
      child: ClipOval(
        child: Image.asset(
          'assets/brand/sfb-pearl.png',
          width: size,
          height: size,
          fit: BoxFit.cover,
          // D6.3's house rule: cap the decode rather than decoding the full asset.
          cacheWidth: (size * MediaQuery.devicePixelRatioOf(context)).round(),
        ),
      ),
    );
  }
}
