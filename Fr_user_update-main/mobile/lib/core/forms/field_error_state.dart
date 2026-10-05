import 'package:flutter/material.dart';

/// Attaches a validation message to the FIELD that caused it, instead of dropping every error in
/// one line at the bottom of the form (D5.6).
///
/// **What this does not change: when errors fire, or which error fires.** Each screen's
/// `_validationError` keeps its existing first-match-wins order and its existing messages; the
/// only difference is that it now also says WHICH field it is talking about, so the message can be
/// rendered against that field and the field can be focused. No validation rule moves, no error
/// contract changes, and nothing new is sent to the backend.
///
/// **Why focus rather than a scroll key.** Requesting focus on a `TextField` inside a scroll view
/// makes Flutter scroll it into view on its own, so "attach the message, move to the field, bring
/// it on screen" needs one call and no per-field `GlobalKey`.
///
/// A screen mixes this in, calls [disposeFieldNodes] from its own `dispose`, builds its fields
/// with [fieldDecoration] and [fieldNode], and reports failures through [showFieldError].
mixin FieldErrorState<T extends StatefulWidget> on State<T> {
  final Map<String, FocusNode> _fieldNodes = {};

  /// The field key the current [fieldErrorMessage] belongs to, or null when the message has no
  /// field of its own — a save failure, or a validation rule about a picker rather than a text box.
  String? fieldErrorKey;

  /// The message itself. Rendered against the field when [fieldErrorKey] is set, and as the
  /// form-level summary line when it is not.
  String? fieldErrorMessage;

  /// True when the message belongs to no particular field, so the screen should render it as the
  /// summary line at the bottom of the form rather than duplicating it against a field.
  bool get hasFormLevelError => fieldErrorMessage != null && fieldErrorKey == null;

  FocusNode fieldNode(String field) => _fieldNodes.putIfAbsent(field, FocusNode.new);

  /// The decoration a field should carry. Identical to a plain [InputDecoration] except that the
  /// message appears here when this is the field that failed.
  InputDecoration fieldDecoration({
    required String label,
    required String field,
    String? helperText,
  }) {
    return InputDecoration(
      labelText: label,
      helperText: helperText,
      errorText: fieldErrorKey == field ? fieldErrorMessage : null,
    );
  }

  /// Shows [message], against [field] when one is named. Focusing the field is what also scrolls
  /// it into view.
  void showFieldError({String? field, required String message}) {
    setState(() {
      fieldErrorKey = field;
      fieldErrorMessage = message;
    });
    if (field != null) _fieldNodes[field]?.requestFocus();
  }

  /// Clears both. Call inside the screen's own `setState`, as the screens already do when a submit
  /// starts.
  void clearFieldError() {
    fieldErrorKey = null;
    fieldErrorMessage = null;
  }

  // ---- The keyboard's next key (walk comment 2, 2026-09-10) -------------------------------------

  /// The screen's TEXT fields, in the order the keyboard's next key should walk them.
  ///
  /// **Why the screen declares this instead of Flutter working it out.** Every field here already
  /// set `textInputAction: TextInputAction.next`, and nothing appeared to happen when the customer
  /// pressed it. The cause is that `next`'s default action is `FocusScope.nextFocus()`, which
  /// moves to the next focusable widget in TRAVERSAL order — and on these screens that is
  /// routinely a `PickerField` button, a `DropdownButtonFormField` or a `CheckboxListTile`. Focus
  /// went to a button, the keyboard closed, and the screen looked inert. It was not a missing
  /// handler so much as a handler doing something the customer could not see.
  ///
  /// A traversal policy could be taught to skip non-text widgets, but the order these screens
  /// want is not always the layout order and half the fields are conditional (a spouse name only
  /// when married, a free-text state only outside Sudan). An explicit list is read at the one
  /// place a reader would look for it, and a conditional field simply is not in it.
  ///
  /// Default empty: a screen that has not opted in keeps Flutter's behaviour exactly.
  List<String> get textFieldOrder => const [];

  /// `next` for every field but the last, `done` for the last.
  ///
  /// Derived rather than written per field, because the two must agree: a field showing `next`
  /// that then submits, or `done` that then moves on, is the same class of untrue affordance as
  /// the dead key this replaces. A field not in [textFieldOrder] keeps `next`.
  TextInputAction fieldAction(String field) {
    final order = textFieldOrder;
    return order.isNotEmpty && order.last == field
        ? TextInputAction.done
        : TextInputAction.next;
  }

  /// The `onSubmitted` handler for [field]: move to the next text field, or — on the last one —
  /// dismiss the keyboard and run [onDone], which screens pass their own submit to.
  ///
  /// So the customer can fill a form and finish it without ever reaching for the on-screen button.
  ValueChanged<String> fieldSubmit(String field, {VoidCallback? onDone}) {
    return (_) {
      final order = textFieldOrder;
      final index = order.indexOf(field);
      if (index < 0) {
        // **Not in the order at all: do nothing.** The first version fell through to the submit
        // branch, which meant `fieldAction` labelled the key «next» while `fieldSubmit` submitted
        // the form — precisely the untrue affordance this pair exists to prevent, and the exact
        // failure a screen declaring a PARTIAL order would get. Unreachable from today's call
        // sites (every caller is in its screen's order in every state); closed because it is the
        // shape the next screen to adopt this would hit. Found by `@agent-reviewer`.
        return;
      }
      if (index < order.length - 1) {
        // `requestFocus` on a field inside a scroll view also scrolls it into view, which is the
        // same property `showFieldError` relies on.
        fieldNode(order[index + 1]).requestFocus();
        return;
      }
      // The last field. Close the keyboard first: leaving it up over a validation message the
      // submit is about to attach is how the customer misses it.
      FocusScope.of(context).unfocus();
      onDone?.call();
    };
  }

  void disposeFieldNodes() {
    for (final node in _fieldNodes.values) {
      node.dispose();
    }
    _fieldNodes.clear();
  }
}

/// What a screen's `_validationError` returns: the message, plus the field it belongs to when it
/// belongs to one. `field` is null for a rule about a picker or a whole-form condition.
typedef FieldValidationError = ({String? field, String message});
