package com.sfbank.bayanati.contactchannels.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves Stage 1b's customer selection plus this deployment's {@code
 * fru.messaging.<channel>.enabled} flags into what actually gets written and challenged.
 *
 * <p><strong>A disabled channel is treated exactly like a deselected one.</strong> The task left
 * this as a design point: a channel this deployment cannot service must not be offered a code, and
 * the honest record of "the customer wanted it but we couldn't" is {@link ChannelState#DECLINED},
 * the same state a deliberate deselection produces — not a fourth state, and not silently
 * pretending the channel was never wanted. This says nothing about how the app should *advertise*
 * availability up front (R-042, correctly out of scope here) — only what the backend does with a
 * selection it cannot honour.
 *
 * <p><strong>Email gets a row only when an address was supplied.</strong> customer.md: "the email
 * channel row activates once an address is entered" — no address means the row was never activated,
 * not that it was declined. A supplied-but-disabled address still gets a row, {@code DECLINED},
 * because the customer did activate it; this deployment just cannot serve it.
 *
 * <p>Pure logic — no Spring, no I/O — exercised by plain JUnit.
 */
public final class ChannelSelection {

  private ChannelSelection() {}

  /**
   * @param emailAddressPresent whether the customer supplied a non-blank email address
   * @throws NoPhoneChannelSelectedException if neither SMS nor WhatsApp ends up {@link
   *     ChannelState#UNVERIFIED} after applying enablement
   */
  public static List<ChannelDecision> resolve(
      boolean smsSelected,
      boolean smsEnabled,
      boolean whatsappSelected,
      boolean whatsappEnabled,
      boolean emailAddressPresent,
      boolean emailEnabled) {
    List<ChannelDecision> decisions = new ArrayList<>();
    decisions.add(new ChannelDecision(MessageChannel.SMS, stateFor(smsSelected, smsEnabled)));
    decisions.add(
        new ChannelDecision(MessageChannel.WHATSAPP, stateFor(whatsappSelected, whatsappEnabled)));
    if (emailAddressPresent) {
      decisions.add(new ChannelDecision(MessageChannel.EMAIL, stateFor(true, emailEnabled)));
    }

    boolean anyPhoneChallenged =
        decisions.stream()
            .filter(d -> d.channel() != MessageChannel.EMAIL)
            .anyMatch(ChannelDecision::toBeChallenged);
    if (!anyPhoneChallenged) {
      throw new NoPhoneChannelSelectedException();
    }

    return List.copyOf(decisions);
  }

  private static ChannelState stateFor(boolean selected, boolean enabled) {
    return selected && enabled ? ChannelState.UNVERIFIED : ChannelState.DECLINED;
  }
}
