package com.sfbank.bayanati.contactchannels.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.contactchannels.domain.NoPhoneChannelSelectedException;
import com.sfbank.bayanati.contactchannels.domain.ProfileAlreadyCompleteException;
import com.sfbank.bayanati.contactchannels.domain.SessionTemporarilyBlockedException;
import com.sfbank.bayanati.contactchannels.service.ChannelOutcome;
import com.sfbank.bayanati.contactchannels.service.ContactChannelsResult;
import com.sfbank.bayanati.contactchannels.service.ContactChannelsService;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The endpoint's contract: the wire shape, the status codes, and what never reaches the service.
 */
@WebMvcTest(ContactChannelsController.class)
// S4-05: see AccountCheckControllerTest's identical comment. Real unauthenticated access is
// proven by ContactChannelsIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class ContactChannelsControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ContactChannelsService contactChannelsService;

  private static final String FULL_BODY =
      "{\"branch\":\"16\",\"accountNumber\":\"0000000001\",\"phoneNumber\":\"+249900004821\","
          + "\"sms\":true,\"whatsapp\":true,\"emailAddress\":\"ahmed@example.invalid\"}";

  @Test
  void aFullRequestReturns200WithTheProfileIdAndChannelList() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(contactChannelsService.submit(
            "16", "0000000001", "+249900004821", true, true, "ahmed@example.invalid"))
        .thenReturn(
            new ContactChannelsResult(
                profileId,
                List.of(
                    new ChannelOutcome(MessageChannel.SMS, ChannelState.UNVERIFIED, "•••• 4821"),
                    new ChannelOutcome(
                        MessageChannel.WHATSAPP, ChannelState.UNVERIFIED, "•••• 4821"),
                    new ChannelOutcome(
                        MessageChannel.EMAIL, ChannelState.UNVERIFIED, "a•••@example.invalid"))));

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(FULL_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileId").value(profileId.toString()))
        .andExpect(jsonPath("$.channels.length()").value(3))
        .andExpect(jsonPath("$.channels[0].channel").value("sms"))
        .andExpect(jsonPath("$.channels[0].state").value("unverified"))
        .andExpect(jsonPath("$.channels[0].maskedDestination").value("•••• 4821"));
  }

  @Test
  void theOtpCodeNeverAppearsAnywhereInTheResponseBody() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(contactChannelsService.submit(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(
            new ContactChannelsResult(
                profileId,
                List.of(
                    new ChannelOutcome(MessageChannel.SMS, ChannelState.UNVERIFIED, "•••• 4821"))));

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\","
                        + "\"phoneNumber\":\"+249900004821\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channels[0].code").doesNotExist())
        .andExpect(jsonPath("$.code").doesNotExist())
        .andExpect(jsonPath("$.otp").doesNotExist());
  }

  @Test
  void omittedSmsAndWhatsappFlagsDefaultToBothSelected() throws Exception {
    when(contactChannelsService.submit(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(new ContactChannelsResult(UUID.randomUUID(), List.of()));

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\","
                        + "\"phoneNumber\":\"+249900004821\"}"))
        .andExpect(status().isOk());

    verify(contactChannelsService).submit("16", "0000000001", "+249900004821", true, true, null);
  }

  @Test
  void explicitFalseFlagsAreHonoured() throws Exception {
    when(contactChannelsService.submit(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(new ContactChannelsResult(UUID.randomUUID(), List.of()));

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\","
                        + "\"phoneNumber\":\"+249900004821\",\"sms\":true,\"whatsapp\":false}"))
        .andExpect(status().isOk());

    verify(contactChannelsService).submit("16", "0000000001", "+249900004821", true, false, null);
  }

  @Test
  void aBlankPhoneNumberIs400AndNeverReachesTheService() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\",\"phoneNumber\":\"  \"}"))
        .andExpect(status().isBadRequest());

    verify(contactChannelsService, never())
        .submit(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void aBlankEmailAddressIsTreatedAsAbsentNotAValidationError() throws Exception {
    when(contactChannelsService.submit(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(new ContactChannelsResult(UUID.randomUUID(), List.of()));

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\","
                        + "\"phoneNumber\":\"+249900004821\",\"emailAddress\":\"   \"}"))
        .andExpect(status().isOk());

    verify(contactChannelsService).submit("16", "0000000001", "+249900004821", true, true, null);
  }

  @Test
  void anOverlongEmailAddressIs400() throws Exception {
    String tooLong =
        "a".repeat(ContactChannelsController.MAX_EMAIL_LENGTH + 1) + "@example.invalid";

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\","
                        + "\"phoneNumber\":\"+249900004821\",\"emailAddress\":\""
                        + tooLong
                        + "\"}"))
        .andExpect(status().isBadRequest());

    verify(contactChannelsService, never())
        .submit(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void aControlCharacterInAnyFieldIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\","
                        + "\"phoneNumber\":\"+2499\\u000000\"}"))
        .andExpect(status().isBadRequest());

    verify(contactChannelsService, never())
        .submit(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void bothPhoneChannelsDeselectedMapsTheDomainExceptionTo400() throws Exception {
    when(contactChannelsService.submit("16", "0000000001", "+249900004821", false, false, null))
        .thenThrow(new NoPhoneChannelSelectedException());

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branch\":\"16\",\"accountNumber\":\"0000000001\","
                        + "\"phoneNumber\":\"+249900004821\",\"sms\":false,\"whatsapp\":false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aTerminalProfileMapsTheDomainExceptionTo409() throws Exception {
    when(contactChannelsService.submit(
            "16", "0000000001", "+249900004821", true, true, "ahmed@example.invalid"))
        .thenThrow(new ProfileAlreadyCompleteException("account already has a completed profile"));

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(FULL_BODY))
        .andExpect(status().isConflict());
  }

  @Test
  void aTemporarilyBlockedReentryMapsTheDomainExceptionTo429() throws Exception {
    when(contactChannelsService.submit(
            "16", "0000000001", "+249900004821", true, true, "ahmed@example.invalid"))
        .thenThrow(
            new SessionTemporarilyBlockedException(
                java.time.Instant.parse("2026-08-30T12:15:00Z")));

    mockMvc
        .perform(
            post("/api/v1/contact-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(FULL_BODY))
        .andExpect(status().isTooManyRequests());
  }
}
