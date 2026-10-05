package com.sfbank.bayanati.contactchannels.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelSelectionTest {

  @Test
  void allThreeChannelsSelectedAndEnabledAreAllChallenged() {
    List<ChannelDecision> decisions = ChannelSelection.resolve(true, true, true, true, true, true);

    assertEquals(3, decisions.size());
    assertTrue(decisions.stream().allMatch(ChannelDecision::toBeChallenged));
  }

  @Test
  void deselectingWhatsAppRecordsItAsDeclinedNotUnverified() {
    List<ChannelDecision> decisions =
        ChannelSelection.resolve(true, true, false, true, false, true);

    assertEquals(2, decisions.size());
    ChannelDecision whatsapp =
        decisions.stream()
            .filter(d -> d.channel() == MessageChannel.WHATSAPP)
            .findFirst()
            .orElseThrow();
    assertEquals(ChannelState.DECLINED, whatsapp.state());
  }

  @Test
  void bothPhoneChannelsDeselectedThrowsBeforeAnyDecisionIsReturned() {
    assertThrows(
        NoPhoneChannelSelectedException.class,
        () -> ChannelSelection.resolve(false, true, false, true, true, true));
  }

  @Test
  void noEmailAddressMeansNoEmailRowAtAll() {
    List<ChannelDecision> decisions = ChannelSelection.resolve(true, true, true, true, false, true);

    assertEquals(2, decisions.size());
    assertTrue(decisions.stream().noneMatch(d -> d.channel() == MessageChannel.EMAIL));
  }

  @Test
  void anEmailAddressWithEmailDisabledStillGetsARowButDeclined() {
    List<ChannelDecision> decisions = ChannelSelection.resolve(true, true, true, true, true, false);

    ChannelDecision email =
        decisions.stream()
            .filter(d -> d.channel() == MessageChannel.EMAIL)
            .findFirst()
            .orElseThrow();
    assertEquals(ChannelState.DECLINED, email.state());
  }

  @Test
  void aDisabledChannelIsTreatedExactlyLikeADeselectedOne() {
    // SMS selected but this deployment has it disabled, WhatsApp deselected outright: neither
    // phone channel is challenged, so this must reject exactly as "both deselected" would.
    assertThrows(
        NoPhoneChannelSelectedException.class,
        () -> ChannelSelection.resolve(true, false, false, true, false, true));
  }

  @Test
  void whatsAppDisabledButSmsSelectedStillProceedsWithSmsOnly() {
    List<ChannelDecision> decisions =
        ChannelSelection.resolve(true, true, true, false, false, true);

    ChannelDecision sms =
        decisions.stream().filter(d -> d.channel() == MessageChannel.SMS).findFirst().orElseThrow();
    ChannelDecision whatsapp =
        decisions.stream()
            .filter(d -> d.channel() == MessageChannel.WHATSAPP)
            .findFirst()
            .orElseThrow();
    assertEquals(ChannelState.UNVERIFIED, sms.state());
    assertEquals(ChannelState.DECLINED, whatsapp.state());
  }
}
