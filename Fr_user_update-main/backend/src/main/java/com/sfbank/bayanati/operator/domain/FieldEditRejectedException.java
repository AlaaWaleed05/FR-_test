package com.sfbank.bayanati.operator.domain;

/**
 * The edit itself is malformed — an unknown {@code fieldKey}, or a value that is absent, blank
 * after trimming, over {@link FieldEditValidator#MAX_VALUE_LENGTH}, or carrying a control
 * character. An ordinary 400: nothing about the profile is wrong, the request is.
 *
 * <p>Deliberately distinct from {@link FieldNotEditableException}, which is a 409. A client that
 * asked to edit a field this profile does not expose is not sending bad syntax — it is asking for
 * something that is not true right now — and collapsing the two would make a stale browser tab
 * indistinguishable from a broken one.
 */
public class FieldEditRejectedException extends RuntimeException {

  public FieldEditRejectedException(String message) {
    super(message);
  }
}
