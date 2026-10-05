import 'package:flutter/material.dart';

/// A value that must read left-to-right inside this right-to-left app: an account number, an
/// OTP code, a `FRU-` reference number, a national number, a Latin name from the identity scan.
///
/// **Why this exists.** Setting `textDirection` alone is not the same as isolating. A value that
/// BEGINS with a Latin run — `FRU-000000001` — inherits the surrounding RTL paragraph direction
/// and the Unicode bidi algorithm reorders its leading and trailing runs, so the customer can be
/// shown a reference number whose parts are in the wrong order. The back office already isolates
/// with `<bdi>` (`backoffice/src/profiles/ProfileDetailPage.tsx:62-64`); mobile had no equivalent.
///
/// **Two mechanisms, deliberately, because they are not interchangeable.**
///
/// 1. [LtrValue] / [LtrValue.selectable] — for a value that STANDS ALONE in its own widget. A
///    `Text` carrying its own `textDirection` is already its own bidi paragraph, and a paragraph
///    boundary is the strongest isolation there is. No control characters are inserted.
///
/// 2. [isolate] — for a value INTERPOLATED INTO an Arabic sentence, where there is no paragraph
///    boundary to rely on because the value and the Arabic share one `Text`. Only there are the
///    FSI/PDI (U+2068/U+2069) characters the right tool.
///
/// **Do not "simplify" case 1 to use [isolate].** Those two characters are invisible but real: at
/// `confirmation_screen.dart` the reference number is a [SelectableText] precisely so the customer
/// can copy it and quote it at a branch, and embedding format characters would put them into
/// whatever they paste — a support agent then searches for a reference number that does not match.
/// Direction on a standalone widget isolates without contaminating the value.
class LtrValue extends StatelessWidget {
  const LtrValue(
    this.value, {
    super.key,
    this.style,
    this.textAlign,
  }) : _selectable = false;

  /// For a value the customer needs to copy — see the class comment on why this still must not
  /// use [isolate].
  const LtrValue.selectable(
    this.value, {
    super.key,
    this.style,
    this.textAlign,
  }) : _selectable = true;

  final String value;
  final TextStyle? style;
  final TextAlign? textAlign;
  final bool _selectable;

  /// Wraps [value] in FSI/PDI so it keeps its own direction when it is interpolated into an
  /// Arabic run that shares one `Text` with it. The isolate is popped explicitly rather than
  /// left to the end of the paragraph, so text AFTER the value is unaffected too.
  ///
  /// The characters are invisible and carry no glyph of their own — the bundled font subset does
  /// not map them, which is correct and not a coverage gap.
  /// Written as escapes, never as the literal characters: they are invisible, so a literal here
  /// would be indistinguishable from a typo and unreviewable in a diff.
  static String isolate(String value) => '\u2068$value\u2069';

  @override
  Widget build(BuildContext context) {
    if (_selectable) {
      return SelectableText(
        value,
        textDirection: TextDirection.ltr,
        textAlign: textAlign,
        style: style,
      );
    }
    return Text(
      value,
      textDirection: TextDirection.ltr,
      textAlign: textAlign,
      style: style,
    );
  }
}
