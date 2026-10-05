package com.sfbank.bayanati.messaging.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-message outcome of exactly one attempt.
 *
 * <p><strong>Every field is a String, an int, a long or a boolean, deliberately.</strong> This
 * record is written straight into {@code audit.audit_event.payload_json}, which is RFC 8785
 * canonical JSON produced by {@code audit.domain.CanonicalJson} — flat objects only, with String /
 * integral / Boolean / null values, everything else rejected (docs/components/persistence.md). An
 * {@code Instant}, a {@code Duration} or a nested object here would fail at the audit write, at
 * runtime, in production, on the notification path. {@link #canonicalPayload()} is the one place
 * this record is turned into the {@code Map} {@code CanonicalJson.object(Map)} accepts, so that
 * conversion exists in exactly one place.
 *
 * <p>Also one-to-one with {@code app.notification_outbox}'s result columns (V0035), so a dispatcher
 * writes this back with a plain parameterised UPDATE and no translation layer.
 *
 * @param messageId the {@link OutboundMessage#messageId()} this result answers, as text
 * @param channel the wire value of the channel this message was sent on
 * @param outcome our closed classification — what the dispatcher acts on
 * @param providerId the configured implementation name, e.g. {@code "bank-gateway"}, {@code "stub"}
 * @param providerMessageId the provider's own id, null when it returns none. This is what a later
 *     delivery receipt correlates on
 * @param providerStatusCode raw, verbatim, exactly as returned. FIB's gateway returns a STRING
 *     ({@code "0"}), not an int — do not parse it, do not normalise it
 * @param providerStatusText raw, verbatim, nullable
 * @param billedSegments UCS-2 segments the provider says it charged for; {@code -1} when unknown or
 *     not applicable (channels other than SMS are not billed in segments). This is the only place
 *     the Arabic cost multiplier becomes measurable rather than estimated — record it from day one
 * @param attemptedAtIso ISO-8601 UTC, server clock
 * @param latencyMillis wall-clock duration of the attempt
 */
public record MessageDispatchResult(
    String messageId,
    String channel,
    DispatchOutcome outcome,
    String providerId,
    String providerMessageId,
    String providerStatusCode,
    String providerStatusText,
    int billedSegments,
    String attemptedAtIso,
    long latencyMillis) {

  /** {@code billedSegments} for a channel with no per-message segment concept, e.g. WhatsApp. */
  public static final int SEGMENTS_NOT_APPLICABLE = -1;

  /**
   * The router's own outcome when no {@link MessageSender} is configured for a message's channel —
   * including a deliberately disabled channel ({@code fru.messaging.<channel>.enabled=false}). Not
   * an exception: this is a state the caller can see and act on, not a programming error.
   */
  public static MessageDispatchResult permanentFailure(
      OutboundMessage message,
      String providerId,
      String providerStatusCode,
      String providerStatusText) {
    return new MessageDispatchResult(
        message.messageId().toString(),
        message.channel().wireValue(),
        DispatchOutcome.PERMANENT_FAILURE,
        providerId,
        null,
        providerStatusCode,
        providerStatusText,
        SEGMENTS_NOT_APPLICABLE,
        Instant.now().toString(),
        0L);
  }

  /**
   * Flattens this record into the {@code Map<String, Object>} shape {@code
   * audit.domain.CanonicalJson.object(Map)} accepts: {@link #outcome()} becomes its {@code name()}
   * (a String), every other field is already a supported type. Proven to round-trip by {@code
   * MessageDispatchResultCanonicalJsonTest}.
   */
  public Map<String, Object> canonicalPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("messageId", messageId);
    payload.put("channel", channel);
    payload.put("outcome", outcome.name());
    payload.put("providerId", providerId);
    payload.put("providerMessageId", providerMessageId);
    payload.put("providerStatusCode", providerStatusCode);
    payload.put("providerStatusText", providerStatusText);
    payload.put("billedSegments", billedSegments);
    payload.put("attemptedAtIso", attemptedAtIso);
    payload.put("latencyMillis", latencyMillis);
    return payload;
  }
}
