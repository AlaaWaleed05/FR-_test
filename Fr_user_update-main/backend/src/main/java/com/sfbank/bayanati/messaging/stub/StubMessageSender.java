package com.sfbank.bayanati.messaging.stub;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Ucs2Segmenter;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for every real provider until the bank supplies a gateway specification (OQ-016) —
 * CLAUDE.md's {@code @agent-researcher}-before-integration rule forbids writing a concrete adapter
 * against documentation alone, and against an unknown gateway there is no documentation to write
 * one against anyway (AD-002c report §5.9).
 *
 * <p><strong>How a caller chooses which destinations return which outcome:</strong> {@link
 * StubMessagingProperties#outcomes()} is a lookup table supplied entirely by configuration, keyed
 * by the raw destination string. Any destination absent from it returns {@link
 * DispatchOutcome#ACCEPTED} — the honest default, mirroring {@code StubCoreBankingClient}.
 *
 * <p>Serves all three channels: the port design (AD-002c report §5) does not require one {@link
 * MessageSender} per channel, only that every channel a caller uses has one mapped to it.
 *
 * <p>Selected by configuration and never by a runtime branch — there is no {@code if (stub)}
 * anywhere in the call path, and this class does not know a real implementation exists. See {@code
 * MessageSenderConfiguration}.
 */
public class StubMessageSender implements MessageSender {

  /** {@link MessageDispatchResult#providerId()} this stub reports. */
  public static final String PROVIDER_ID = "stub";

  /**
   * One recorded attempt. Deliberately NOT the {@link OutboundMessage} itself: {@code payload}
   * carries the rendered body, which for an SMS/WhatsApp OTP contains the code — and AD-002c report
   * §5.3 states the port's whole point is that there is "no way for a caller to ask the port what
   * code was sent" (the structural fix for the FIB defect in §3.1). {@code app.otp_challenge}'s own
   * rule is that the code is NEVER stored; a recorder that kept the payload would violate that rule
   * the moment {@code stub} is also this deployment's real provider (it is, until a gateway
   * specification exists), retaining it in memory for the life of the process.
   */
  public record SentMessage(UUID messageId, MessageChannel channel, String destination) {}

  private final StubMessagingProperties properties;

  /**
   * Every attempt this stub has sent, in order — AD-002c report §5.9: "records the attempt where a
   * test can read it". Only populated when {@link StubMessagingProperties#recordSends()} is true
   * (default false): "a test can read it" describes a test-time convenience, not a standing
   * production log, and {@code stub} is not test-only — it is the only accepted provider until a
   * real adapter exists, so an always-on recorder would grow without bound for the life of a real
   * deployment. {@link CopyOnWriteArrayList} because a real dispatcher could call {@code send} from
   * more than one thread even though this stub itself is single-threaded logic.
   */
  private final List<SentMessage> recordedSends = new CopyOnWriteArrayList<>();

  public StubMessageSender(StubMessagingProperties properties) {
    this.properties = properties;
  }

  /**
   * Read-only view of every message passed to {@link #send(OutboundMessage)} so far. Empty unless
   * {@code fru.messaging.stub.record-sends=true} — see {@link #recordedSends} for why recording is
   * opt-in.
   */
  public List<SentMessage> recordedSends() {
    return Collections.unmodifiableList(recordedSends);
  }

  @Override
  public Set<MessageChannel> supportedChannels() {
    return Set.of(MessageChannel.SMS, MessageChannel.WHATSAPP, MessageChannel.EMAIL);
  }

  @Override
  public MessageDispatchResult send(OutboundMessage message) {
    if (properties.recordSends()) {
      recordedSends.add(
          new SentMessage(message.messageId(), message.channel(), message.destination()));
    }

    DispatchOutcome outcome =
        properties.outcomes().getOrDefault(message.destination(), DispatchOutcome.ACCEPTED);

    int billedSegments =
        message.channel() == MessageChannel.SMS
            ? Ucs2Segmenter.segments(((SmsPayload) message.payload()).body())
            : MessageDispatchResult.SEGMENTS_NOT_APPLICABLE;

    if (properties.latencyMillis() > 0) {
      sleep(properties.latencyMillis());
    }

    boolean accepted = outcome == DispatchOutcome.ACCEPTED;
    return new MessageDispatchResult(
        message.messageId().toString(),
        message.channel().wireValue(),
        outcome,
        PROVIDER_ID,
        accepted ? "stub-" + UUID.randomUUID() : null,
        outcome.name(),
        accepted ? "accepted by stub" : "seeded outcome for destination " + message.destination(),
        billedSegments,
        Instant.now().toString(),
        properties.latencyMillis());
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
