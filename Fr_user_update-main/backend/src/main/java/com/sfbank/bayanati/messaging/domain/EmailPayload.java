package com.sfbank.bayanati.messaging.domain;

/**
 * @param subject the rendered subject line.
 * @param bodyText the rendered plain-text body.
 * @param bodyHtml the rendered HTML body, or {@code null} when this message is plain-text only.
 */
public record EmailPayload(String subject, String bodyText, String bodyHtml)
    implements MessagePayload {

  public EmailPayload {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject must not be blank");
    }
    if (bodyText == null || bodyText.isBlank()) {
      throw new IllegalArgumentException("bodyText must not be blank");
    }
  }

  @Override
  public MessageChannel channel() {
    return MessageChannel.EMAIL;
  }
}
