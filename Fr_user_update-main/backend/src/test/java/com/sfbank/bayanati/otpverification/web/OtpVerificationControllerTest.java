package com.sfbank.bayanati.otpverification.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.otpverification.domain.EmailCorrectionNotApplicableException;
import com.sfbank.bayanati.otpverification.domain.ResendOutcome;
import com.sfbank.bayanati.otpverification.domain.UnknownOtpChannelException;
import com.sfbank.bayanati.otpverification.domain.VerificationOutcome;
import com.sfbank.bayanati.otpverification.service.OtpVerificationService;
import com.sfbank.bayanati.otpverification.service.ResendAttemptResult;
import com.sfbank.bayanati.otpverification.service.VerificationAttemptResult;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OtpVerificationController.class)
// S4-05: see AccountCheckControllerTest's identical comment. Real unauthenticated access is
// proven by OtpVerificationIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class OtpVerificationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OtpVerificationService otpVerificationService;

  @Test
  void aCorrectVerifyReturns200WithTheOutcome() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(otpVerificationService.verify(profileId, MessageChannel.SMS, "123456"))
        .thenReturn(
            new VerificationAttemptResult(
                MessageChannel.SMS, VerificationOutcome.VERIFIED, ChannelState.VERIFIED, null));

    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"sms\",\"code\":\"123456\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel").value("sms"))
        .andExpect(jsonPath("$.outcome").value("VERIFIED"))
        .andExpect(jsonPath("$.state").value("verified"))
        .andExpect(jsonPath("$.sessionBlockedUntilIso").doesNotExist());
  }

  @Test
  void theSubmittedCodeNeverAppearsInTheResponseBody() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(otpVerificationService.verify(any(), any(), any()))
        .thenReturn(
            new VerificationAttemptResult(
                MessageChannel.SMS, VerificationOutcome.WRONG_CODE, ChannelState.UNVERIFIED, null));

    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"sms\",\"code\":\"999999\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").doesNotExist())
        .andExpect(jsonPath("$.hash").doesNotExist());
  }

  @Test
  void aSessionBlockIsReflectedInTheResponse() throws Exception {
    UUID profileId = UUID.randomUUID();
    Instant until = Instant.parse("2026-08-30T12:15:00Z");
    when(otpVerificationService.verify(any(), any(), any()))
        .thenReturn(
            new VerificationAttemptResult(
                MessageChannel.SMS,
                VerificationOutcome.CHANNEL_LOCKED,
                ChannelState.UNVERIFIED,
                until));

    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"sms\",\"code\":\"999999\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("CHANNEL_LOCKED"))
        .andExpect(jsonPath("$.sessionBlockedUntilIso").value(until.toString()));
  }

  @Test
  void aNonSixDigitCodeIs400AndNeverReachesTheService() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + UUID.randomUUID()
                        + "\",\"channel\":\"sms\",\"code\":\"12345\"}"))
        .andExpect(status().isBadRequest());

    verify(otpVerificationService, never()).verify(any(), any(), any());
  }

  @Test
  void aNonNumericCodeIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + UUID.randomUUID()
                        + "\",\"channel\":\"sms\",\"code\":\"12a456\"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * ARABIC-INDIC DIGITS ARE REFUSED, and this is a security boundary rather than input tidiness.
   *
   * <p>«٠٠٠٠٠١» is six Unicode decimal digits, so the {@code Character.isDigit} gate this endpoint
   * used until S9-07 admitted it. {@code OtpCodeGenerator.hash} encodes with {@code US_ASCII},
   * which turns each of them into {@code '?'} before hashing — so these six characters, and any
   * other six Arabic-Indic digits, all hash identically. Against a challenge whose stored hash was
   * itself computed from a non-ASCII code (which is what the generator produced on an
   * Arabic-default JVM before the same commit fixed it), they verified.
   *
   * <p>Either fix closes the hole alone. Both are kept, and this one is asserted here, because a
   * boundary that accepts what the hash cannot represent is wrong whatever the generator does — and
   * because it is what CLAUDE.md's "reject non-ASCII digits at the boundary" already required.
   */
  @Test
  void anArabicIndicDigitCodeIs400AndNeverReachesTheService() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + UUID.randomUUID()
                        + "\",\"channel\":\"sms\",\"code\":\"٠٠٠٠٠١\"}"))
        .andExpect(status().isBadRequest());

    verify(otpVerificationService, never()).verify(any(), any(), any());
  }

  @Test
  void aMalformedProfileIdIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"not-a-uuid\",\"channel\":\"sms\",\"code\":\"123456\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anUnrecognisedChannelIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + UUID.randomUUID()
                        + "\",\"channel\":\"carrier-pigeon\",\"code\":\"123456\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownOtpChannelExceptionMapsTo400() throws Exception {
    when(otpVerificationService.verify(any(), any(), any()))
        .thenThrow(new UnknownOtpChannelException("no such channel"));

    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + UUID.randomUUID()
                        + "\",\"channel\":\"email\",\"code\":\"123456\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aResendReturns200WithTheOutcomeAndMaskedDestination() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(otpVerificationService.resend(profileId, MessageChannel.SMS, null))
        .thenReturn(
            new ResendAttemptResult(MessageChannel.SMS, ResendOutcome.ISSUED, "•••• 4821", -1));

    mockMvc
        .perform(
            post("/api/v1/otp/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"channel\":\"sms\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ISSUED"))
        .andExpect(jsonPath("$.maskedDestination").value("•••• 4821"));
  }

  @Test
  void aTooSoonResendReturnsTheSecondsRemaining() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(otpVerificationService.resend(eq(profileId), eq(MessageChannel.SMS), isNull()))
        .thenReturn(
            new ResendAttemptResult(MessageChannel.SMS, ResendOutcome.TOO_SOON, "•••• 4821", 20));

    mockMvc
        .perform(
            post("/api/v1/otp/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"channel\":\"sms\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("TOO_SOON"))
        .andExpect(jsonPath("$.secondsUntilAllowed").value(20));
  }

  @Test
  void aResendWithACorrectedEmailForwardsItToTheService() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(otpVerificationService.resend(profileId, MessageChannel.EMAIL, "fixed@example.com"))
        .thenReturn(
            new ResendAttemptResult(
                MessageChannel.EMAIL, ResendOutcome.ISSUED, "f•••@example.com", -1));

    mockMvc
        .perform(
            post("/api/v1/otp/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"email\",\"correctedEmailAddress\":\"fixed@example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ISSUED"))
        .andExpect(jsonPath("$.maskedDestination").value("f•••@example.com"));

    verify(otpVerificationService).resend(profileId, MessageChannel.EMAIL, "fixed@example.com");
  }

  @Test
  void anEmailCorrectionNotApplicableExceptionMapsTo400() throws Exception {
    // Changing the phone number is deliberately out of scope -- the journey routes that back to
    // Stage 1b, because it changes what the session authenticates against. The guard itself lives
    // in OtpVerificationService (CLAUDE.md's business-logic-in-service rule) -- this test proves
    // only that the controller maps its exception to 400, not the guard's own logic.
    UUID profileId = UUID.randomUUID();
    when(otpVerificationService.resend(profileId, MessageChannel.SMS, "fixed@example.com"))
        .thenThrow(
            new EmailCorrectionNotApplicableException(
                "correctedEmailAddress is only applicable to the email channel, not sms"));

    mockMvc
        .perform(
            post("/api/v1/otp/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"sms\",\"correctedEmailAddress\":\"fixed@example.com\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anEmailCorrectionExceedingTheLengthCapIs400() throws Exception {
    UUID profileId = UUID.randomUUID();
    String tooLong = "a".repeat(255) + "@example.com";

    mockMvc
        .perform(
            post("/api/v1/otp/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"email\",\"correctedEmailAddress\":\""
                        + tooLong
                        + "\"}"))
        .andExpect(status().isBadRequest());

    verify(otpVerificationService, never()).resend(any(), any(), any());
  }
}
