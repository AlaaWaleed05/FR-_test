package com.sfbank.bayanati.accountcheck.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.accountcheck.domain.AccountCheckContinuation;
import com.sfbank.bayanati.accountcheck.domain.AccountCheckOutcome;
import com.sfbank.bayanati.accountcheck.service.AccountCheckResult;
import com.sfbank.bayanati.accountcheck.service.AccountCheckService;
import java.time.Instant;
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
@WebMvcTest(AccountCheckController.class)
// S4-05: spring-boot-starter-security on the classpath makes @WebMvcTest slices require
// authentication by default (they don't load auth.config.SecurityConfiguration's real chains).
// This slice tests controller/validation logic only; the real unauthenticated access to this
// same endpoint is proven end to end by AccountCheckIntegrationTest (@SpringBootTest, full
// context, chain 2 permitAll).
@AutoConfigureMockMvc(addFilters = false)
class AccountCheckControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccountCheckService accountCheckService;

  private static final String ACTIVE_BODY = "{\"branch\":\"16\",\"accountNumber\":\"0000000001\"}";

  @Test
  void anActiveAccountIs200WithProceed() throws Exception {
    UUID requestId = UUID.randomUUID();
    when(accountCheckService.check("16", "0000000001"))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.ACTIVE, AccountCheckContinuation.PROCEED, requestId, null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACTIVE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ACTIVE"))
        .andExpect(jsonPath("$.continuation").value("PROCEED"))
        .andExpect(jsonPath("$.requestId").value(requestId.toString()));
  }

  @Test
  void aCoreBankingOutageIs503NotA200WithAnOutcome() throws Exception {
    // S3-02 / BL-008: the middleware's -1 "System Error", a timeout, or an unreadable reply. Not a
    // journey outcome the customer can act on, so not a 200 -- and not a new outcome value either,
    // which would have broken the app's exhaustive outcome switches. The app already treats any
    // 5xx as "try again later".
    when(accountCheckService.check("16", "0000000002"))
        .thenThrow(
            new com.sfbank.bayanati.corebanking.domain.CoreBankingUnavailableException(
                "the core banking middleware reported a system error", null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"16\",\"accountNumber\":\"0000000002\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(jsonPath("$.outcome").doesNotExist());
  }

  @Test
  void anActiveAccountWithACompleteProfileIs200WithTerminalNotProceed() throws Exception {
    // S3-07: the controller must report AccountCheckResult's own continuation, not derive it from
    // AccountCheckOutcome.ACTIVE.continuation() (which is always PROCEED) -- ACTIVE + TERMINAL is
    // exactly the combination the profile-existence check produces for an already-complete profile.
    when(accountCheckService.check("16", "0000000001"))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.ACTIVE,
                AccountCheckContinuation.TERMINAL,
                UUID.randomUUID(),
                null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACTIVE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ACTIVE"))
        .andExpect(jsonPath("$.continuation").value("TERMINAL"));
  }

  @Test
  void anActiveAccountUnderAnActivePhoneLockIs200WithBlockedAndATimestamp() throws Exception {
    // S4-06/BL-021: customer.md Stage 0's "blocked until [time]" screen. blockedUntil must be
    // carried through to the wire exactly as AccountCheckService reports it.
    Instant blockedUntil = Instant.parse("2026-09-02T10:15:00Z");
    when(accountCheckService.check("16", "0000000001"))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.ACTIVE,
                AccountCheckContinuation.BLOCKED,
                UUID.randomUUID(),
                blockedUntil));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACTIVE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ACTIVE"))
        .andExpect(jsonPath("$.continuation").value("BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(blockedUntil.toString()));
  }

  @Test
  void aProceedResponseCarriesNoBlockedUntil() throws Exception {
    when(accountCheckService.check("16", "0000000001"))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.ACTIVE,
                AccountCheckContinuation.PROCEED,
                UUID.randomUUID(),
                null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACTIVE_BODY))
        .andExpect(status().isOk())
        // Explicit null, not doesNotExist(): the field is always serialised (no Jackson NON_NULL
        // inclusion configured), so doesNotExist() alone would pass for a present-but-null value
        // too (found by @agent-reviewer, S4-06, second pass).
        .andExpect(jsonPath("$.blockedUntil").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void anInvalidAccountIs200WithRetryNotA404() throws Exception {
    when(accountCheckService.check("16", "9999999999"))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.INVALID,
                AccountCheckContinuation.RETRY,
                UUID.randomUUID(),
                null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"16\",\"accountNumber\":\"9999999999\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("INVALID"))
        .andExpect(jsonPath("$.continuation").value("RETRY"));
  }

  @Test
  void theRawResultCodeIsNeverReturnedToTheHandset() throws Exception {
    when(accountCheckService.check("16", "0000000001"))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.ACTIVE,
                AccountCheckContinuation.PROCEED,
                UUID.randomUUID(),
                null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACTIVE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultCode").doesNotExist());
  }

  @Test
  void aBlankBranchIs400AndNeverReachesTheCoreBankingSystem() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"  \",\"accountNumber\":\"0000000001\"}"))
        .andExpect(status().isBadRequest());

    verify(accountCheckService, never()).check(anyString(), anyString());
  }

  @Test
  void aMissingAccountNumberIs400AndNeverReachesTheCoreBankingSystem() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"16\"}"))
        .andExpect(status().isBadRequest());

    verify(accountCheckService, never()).check(anyString(), anyString());
  }

  /**
   * The bug the obvious validation order hides: isBlank() is false for U+0001, trim() strips it, so
   * a check-then-trim would forward — and audit — an empty account number.
   */
  @Test
  void aControlCharacterOnlyAccountNumberIs400NotAnEmptyAccountCheck() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"16\",\"accountNumber\":\"\\u0001\"}"))
        .andExpect(status().isBadRequest());

    verify(accountCheckService, never()).check(anyString(), anyString());
  }

  /**
   * An interior NUL survives trim(), and audit.audit_event.payload is a generated
   * payload_json::jsonb column that PostgreSQL refuses \u0000 for — so without this it would be a
   * 500 with nothing recorded, from an unauthenticated request.
   */
  @Test
  void anInteriorControlCharacterIs400NotA500FromTheJsonbCast() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"16\",\"accountNumber\":\"00\\u000011\"}"))
        .andExpect(status().isBadRequest());

    verify(accountCheckService, never()).check(anyString(), anyString());
  }

  @Test
  void anOverlongFieldIs400SoAnUnauthenticatedRequestCannotBloatTheAuditTrail() throws Exception {
    String tooLong = "0".repeat(AccountCheckController.MAX_FIELD_LENGTH + 1);

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"16\",\"accountNumber\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest());

    verify(accountCheckService, never()).check(anyString(), anyString());
  }

  @Test
  void aFieldExactlyAtTheLengthCapIsAccepted() throws Exception {
    String atCap = "0".repeat(AccountCheckController.MAX_FIELD_LENGTH);
    when(accountCheckService.check("16", atCap))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.INVALID,
                AccountCheckContinuation.RETRY,
                UUID.randomUUID(),
                null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\"16\",\"accountNumber\":\"" + atCap + "\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void surroundingWhitespaceIsTrimmedBeforeTheCoreBankingCall() throws Exception {
    when(accountCheckService.check("16", "0000000001"))
        .thenReturn(
            new AccountCheckResult(
                AccountCheckOutcome.ACTIVE,
                AccountCheckContinuation.PROCEED,
                UUID.randomUUID(),
                null));

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branch\":\" 16 \",\"accountNumber\":\" 0000000001 \"}"))
        .andExpect(status().isOk());

    verify(accountCheckService).check("16", "0000000001");
  }
}
