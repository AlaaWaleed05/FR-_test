package com.sfbank.bayanati.contactchannels.domain;

/**
 * Thrown when, after applying the customer's selection and this deployment's {@code
 * fru.messaging.<channel>.enabled} flags, neither SMS nor WhatsApp ends up challenged. customer.md,
 * Stage 1b: "At least one must remain selected... presented as a requirement, not as an error",
 * enforced here server-side rather than only in the app (BL-007's sibling concern: an
 * unauthenticated client must not be able to create a profile with no verifiable phone contact by
 * skipping the app's own UI rule).
 */
public class NoPhoneChannelSelectedException extends RuntimeException {

  public NoPhoneChannelSelectedException() {
    super("at least one phone channel (SMS or WhatsApp) must be selected and available");
  }
}
