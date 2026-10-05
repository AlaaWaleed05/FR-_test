import 'package:flutter/material.dart';

/// The action row a journey screen ends with, held clear of the system navigation bar.
///
/// **Why this exists: the buttons were unreachable.** Walk comment 9 of the 2026-09-10 walk, on
/// a Samsung A55 with the navigation bar set to BUTTONS rather than swipe gestures. Nine screens
/// wrapped their body in `SafeArea` and the form screens did not, so on those the «التالي» button
/// sat underneath the system bar. Not merely clipped — a tap in that strip goes to the navigation bar, so the
/// customer cannot advance the journey at all. Gesture navigation hides this completely, which is
/// why it survived every emulator run and the whole pilot on a gesture handset.
///
/// **`SafeArea` and not a hand-rolled `MediaQuery.paddingOf` read.** The inset is only present
/// when there is something to avoid, so this is a no-op on a gesture handset rather than dead
/// space. `top: false` because these bars sit at the bottom of a `Column` beneath a `BrandBanner`
/// — `Scaffold` has already consumed the top inset for the app bar, and asking for it twice would
/// push the row down for nothing.
///
/// **The keyboard is not this widget's problem.** `Scaffold.resizeToAvoidBottomInset` shrinks the
/// body when the keyboard opens, and `MediaQuery.padding.bottom` is zeroed while it is up, so the
/// row rides above the keyboard with no `viewInsets` arithmetic here. That is worth stating
/// because the obvious "fix" — adding `viewInsets.bottom` — would double-count and open a gap.
///
/// Two shapes, because the screens have two:
///
/// - [StageActionBar.new] takes any child, for the screens with one full-width button or a
///   composed block (a status line above the button, a secondary link below it).
/// - [StageActionBar.previousNext] is the «السابق» / «التالي» pair that stages 4, 5 and 6 repeat,
///   including the in-flight spinner that swaps for the label. Extracted so the spinner's size
///   and the gap between the buttons are decided once. (Stage 7 used it too until walk comment 4
///   made its cards the forward action; it now uses the `child` constructor for «السابق» alone.)
class StageActionBar extends StatelessWidget {
  /// An arbitrary action block — padded, and held above the navigation bar.
  const StageActionBar({super.key, required Widget this.child})
    : onPrevious = null,
      onNext = null,
      busy = false,
      nextLabel = null;

  /// The «السابق» / «التالي» pair. [onPrevious] omitted renders the primary button alone at full
  /// width, which is what Stage 3 and Stage 1a/1b need — they have no back to offer.
  ///
  /// [onNext] is nullable rather than required-non-null because a disabled primary action is a
  /// real state on these screens (nothing selected yet, a validation precondition unmet), and
  /// `null` is how Flutter spells it.
  const StageActionBar.previousNext({
    super.key,
    this.onPrevious,
    required this.onNext,
    this.busy = false,
    this.nextLabel = 'التالي',
  }) : child = null;

  final Widget? child;
  final VoidCallback? onPrevious;
  final VoidCallback? onNext;

  /// True while the screen's own submit is in flight. Swaps the primary label for a spinner and
  /// is NOT itself what disables the button — the screens already pass `null` to [onNext] for
  /// that, and doing it in two places would let the two disagree.
  final bool busy;

  final String? nextLabel;

  /// The spinner that replaces the primary label in flight. 20 dp at stroke 2 is the size every
  /// screen was already using inline.
  static const Widget _busyIndicator = SizedBox(
    width: 20,
    height: 20,
    child: CircularProgressIndicator(strokeWidth: 2),
  );

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: child ?? _navigation(),
      ),
    );
  }

  Widget _navigation() {
    final next = FilledButton(
      onPressed: busy ? null : onNext,
      // `??` rather than `!`: the field is nullable (the `child` constructor sets it null), so an
      // explicit `nextLabel: null` would otherwise throw. No call site does that today — closed
      // because a latent `!` on a public API is a trap, not because it is reachable now.
      child: busy ? _busyIndicator : Text(nextLabel ?? 'التالي'),
    );
    if (onPrevious == null) return next;
    return Row(
      children: [
        Expanded(
          child: OutlinedButton(onPressed: onPrevious, child: const Text('السابق')),
        ),
        const SizedBox(width: 12),
        Expanded(child: next),
      ],
    );
  }
}
