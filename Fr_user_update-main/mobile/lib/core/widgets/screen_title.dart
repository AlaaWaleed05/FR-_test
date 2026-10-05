import 'package:flutter/material.dart';

/// An `AppBar` title that shrinks to fit instead of truncating.
///
/// Walk comment 5b, filed as W-3 and treated as a DEFECT rather than a wording preference: the OTP
/// screen's title truncated to «التحقق من وسائل ال...» on the pilot handset. Renaming the screen
/// would have hidden it — the same bar will truncate again on any long title, and sooner at the
/// 1.3× text scale S8-03 committed to supporting, because the abandon action sitting in `actions`
/// takes its width first.
///
/// `BoxFit.scaleDown` only ever shrinks, so a title that already fits is untouched and renders at
/// the exact theme size. `alignment` follows the reading direction rather than naming a side, so
/// this stays correct if anything ever renders LTR.
class ScreenTitle extends StatelessWidget {
  const ScreenTitle(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return FittedBox(
      fit: BoxFit.scaleDown,
      alignment: AlignmentDirectional.centerStart,
      child: Text(text),
    );
  }
}
