package com.sfbank.bayanati.messaging.domain;

/**
 * What actually goes on the wire, already rendered. Rendering from a template key plus parameters
 * is business logic and belongs in a domain/service class (BL-005 owns the copy); by the time a
 * payload reaches {@link MessageSender} there is nothing left to decide.
 *
 * <p>Sealed rather than one bag-of-strings because the three channels differ irreducibly: SMS
 * carries a body and a sender id; email carries a subject; WhatsApp carries no free text at all,
 * only a template name registered with Meta plus positional parameters. Flattening that into one
 * record would force every implementation to validate by hand what the compiler can check here.
 */
public sealed interface MessagePayload permits SmsPayload, WhatsAppPayload, EmailPayload {
  MessageChannel channel();
}
