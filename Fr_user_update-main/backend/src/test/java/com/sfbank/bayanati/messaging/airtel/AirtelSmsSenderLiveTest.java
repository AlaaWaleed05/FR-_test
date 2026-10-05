package com.sfbank.bayanati.messaging.airtel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.messaging.config.MessageSenderConfigurationTestAccess;
import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The real Airtel gateway, called through the real adapter. <strong>This sends an actual SMS to an
 * actual handset and costs actual money</strong>, so it is inert unless every value is supplied in
 * the environment and the {@code live} group is enabled; {@code live} is excluded by default
 * alongside {@code integration} in pom.xml.
 *
 * <p>Why it exists: {@code AirtelSmsSenderTest} proves the adapter against what we <em>believe</em>
 * the gateway sends — the 2026-09-07 Postman capture. This proves it against what the gateway
 * <em>does</em> send, which is the only thing that closes road map 1.1's "a real code arrives on a
 * real handset".
 *
 * <p><strong>Nothing here is committed and nothing is printed.</strong> The endpoint, both
 * credentials, the sender id and the destination all come from the environment and nowhere else —
 * CLAUDE.md forbids them in the repository, in a log and in a session report. The assertions are on
 * the SHAPE of the answer, never on a value: no number, no credential and no message id is printed
 * on failure.
 *
 * <p>Run:
 *
 * <pre>
 * FRU_AIRTEL_LIVE_ENDPOINT=&lt;url&gt; FRU_AIRTEL_LIVE_USERNAME=&lt;user&gt; \
 * FRU_AIRTEL_LIVE_PASSWORD=&lt;password&gt; FRU_AIRTEL_LIVE_SENDER=&lt;sender id&gt; \
 * FRU_AIRTEL_LIVE_DESTINATION=+249… \
 * ./mvnw test -Dgroups=live -Dexcluded.test.groups= -Dtest=AirtelSmsSenderLiveTest
 * </pre>
 *
 * <p><strong>R-041 caveat, which this test cannot discharge:</strong> an {@code ACCEPTED} result
 * means Airtel took the message, not that a handset received it. The sender id is unregistered by
 * product-owner decision, and an unregistered sender can be dropped by a network with no error. A
 * green run here plus a silent handset is exactly R-041's symptom — check the handset, not just
 * this assertion.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FRU_AIRTEL_LIVE_ENDPOINT", matches = "https?://.+")
@EnabledIfEnvironmentVariable(named = "FRU_AIRTEL_LIVE_DESTINATION", matches = "\\+249\\d{9}")
class AirtelSmsSenderLiveTest {

  private AirtelSmsSender sender() {
    AirtelSmsProperties properties =
        new AirtelSmsProperties(
            URI.create(System.getenv("FRU_AIRTEL_LIVE_ENDPOINT")),
            System.getenv("FRU_AIRTEL_LIVE_USERNAME"),
            System.getenv("FRU_AIRTEL_LIVE_PASSWORD"),
            System.getenv("FRU_AIRTEL_LIVE_SENDER"),
            Duration.ofSeconds(5),
            Duration.ofSeconds(15));
    return new AirtelSmsSender(
        MessageSenderConfigurationTestAccess.restClient(properties), properties);
  }

  private static OutboundMessage message(String body) {
    return new OutboundMessage(
        UUID.randomUUID(),
        MessageChannel.SMS,
        System.getenv("FRU_AIRTEL_LIVE_DESTINATION"),
        new SmsPayload(body),
        Urgency.INTERACTIVE,
        "live-test");
  }

  /**
   * The Arabic body is the point: every SMS this product sends is Arabic, it forces UCS-2, and the
   * 2026-09-07 capture showed it bills 2 segments. This asserts the adapter reads that count back.
   */
  @Test
  void aRealArabicMessageIsAcceptedAndBillsItsSegments() {
    MessageDispatchResult result = sender().send(message("رمز التحقق: 123456"));

    assertEquals(
        DispatchOutcome.ACCEPTED,
        result.outcome(),
        "the gateway did not accept the message; outcome only, see the logs for the shape");
    assertNotNull(result.providerMessageId(), "an accepted send must carry Airtel's apiMsgId");
    assertTrue(result.billedSegments() > 0, "an accepted Arabic send must report billed units");
    assertEquals(AirtelSmsSender.PROVIDER_ID, result.providerId());
  }

  /**
   * The failure path against the real gateway, using a number that cannot be a real handset. Proves
   * the parser reads Airtel's own reason text rather than reporting a failed send as delivered —
   * the defect BL-080 warns ships silently because HTTP status is 200 on both paths.
   */
  @Test
  void anImpossibleNumberIsRejectedWithTheGatewaysOwnReason() {
    OutboundMessage toNowhere =
        new OutboundMessage(
            UUID.randomUUID(),
            MessageChannel.SMS,
            "+249000000000",
            new SmsPayload("رمز التحقق: 123456"),
            Urgency.INTERACTIVE,
            "live-test");

    MessageDispatchResult result = sender().send(toNowhere);

    assertEquals(DispatchOutcome.REJECTED, result.outcome());
    assertNotNull(result.providerStatusText(), "a rejection must carry the provider's own words");
  }
}
