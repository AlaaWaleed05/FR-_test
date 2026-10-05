package com.sfbank.bayanati.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.audit.domain.CanonicalJson;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task this record exists to satisfy: {@code MessageDispatchResult} must survive {@code
 * audit.domain.CanonicalJson}, which accepts only flat objects of String/integral/Boolean/null and
 * rejects everything else (docs/components/persistence.md). Proves it round-trips, for both a
 * fully-populated result and one carrying the nulls a real REJECTED/TRANSIENT_FAILURE attempt
 * produces.
 */
class MessageDispatchResultCanonicalJsonTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @Test
  void aFullyPopulatedResultRoundTripsUnchanged() {
    MessageDispatchResult result =
        new MessageDispatchResult(
            UUID.randomUUID().toString(),
            "sms",
            DispatchOutcome.ACCEPTED,
            "stub",
            "provider-msg-123",
            "0",
            "success",
            2,
            "2026-08-29T12:00:00Z",
            42L);

    // This is the exact call OutboxDispatcher makes. If canonicalPayload() produced anything
    // CanonicalJson.object rejects (a nested object, an Instant, an enum-as-object), this line
    // throws IllegalArgumentException instead of returning JSON.
    String json = CanonicalJson.object(result.canonicalPayload());

    JsonNode node = jsonMapper.readTree(json);
    assertEquals(result.messageId(), node.get("messageId").asText());
    assertEquals(result.channel(), node.get("channel").asText());
    assertEquals(result.outcome().name(), node.get("outcome").asText());
    assertEquals(result.providerId(), node.get("providerId").asText());
    assertEquals(result.providerMessageId(), node.get("providerMessageId").asText());
    assertEquals(result.providerStatusCode(), node.get("providerStatusCode").asText());
    assertEquals(result.providerStatusText(), node.get("providerStatusText").asText());
    assertEquals(result.billedSegments(), node.get("billedSegments").asInt());
    assertEquals(result.attemptedAtIso(), node.get("attemptedAtIso").asText());
    assertEquals(result.latencyMillis(), node.get("latencyMillis").asLong());
  }

  @Test
  void aResultWithNullProviderMessageIdRoundTrips() {
    // The shape a REJECTED/TRANSIENT_FAILURE/PERMANENT_FAILURE attempt actually produces:
    // providerMessageId is null (CanonicalJson supports null values explicitly).
    MessageDispatchResult result =
        new MessageDispatchResult(
            UUID.randomUUID().toString(),
            "whatsapp",
            DispatchOutcome.REJECTED,
            "stub",
            null,
            "REJECTED",
            "seeded outcome for destination +249900000001",
            MessageDispatchResult.SEGMENTS_NOT_APPLICABLE,
            "2026-08-29T12:00:01Z",
            5L);

    String json = CanonicalJson.object(result.canonicalPayload());
    JsonNode node = jsonMapper.readTree(json);

    assertTrue(node.has("providerMessageId"));
    assertTrue(
        node.get("providerMessageId").isNull(),
        "expected JSON null, got: " + node.get("providerMessageId"));
    assertEquals(-1, node.get("billedSegments").asInt());
  }
}
