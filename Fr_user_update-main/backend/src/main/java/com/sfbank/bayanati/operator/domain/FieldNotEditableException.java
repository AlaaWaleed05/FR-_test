package com.sfbank.bayanati.operator.domain;

/**
 * This field cannot be edited on this profile right now — either the profile's status is outside
 * {@code {submitted, rejected}}, or {@link EditableFieldPolicy} does not include the field for this
 * particular profile (a Sudan address has no free-text state to edit; a scanned birth city
 * supersedes the customer's own). An ordinary 409: the request was well-formed, the profile just
 * isn't in a state this action applies to.
 *
 * <p>The name avoids {@code ProfileNotEditableException}, which {@code dataentry.domain} already
 * owns for the customer-side "profile has reached a terminal status" case.
 */
public class FieldNotEditableException extends RuntimeException {

  public FieldNotEditableException(String message) {
    super(message);
  }
}
