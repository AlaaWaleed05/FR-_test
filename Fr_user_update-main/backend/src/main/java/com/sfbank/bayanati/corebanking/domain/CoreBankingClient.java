package com.sfbank.bayanati.corebanking.domain;

/**
 * The bank's core banking system, reached through its middleware's {@code CheckAccount} call
 * (AD-007, 2026-09-04). The core is READ-ONLY to this solution (PROJECT_PLAN.md Constraints): there
 * is no write path.
 *
 * <p>A secure HTTPS/JSON {@code POST .../OMNI_PH3/resources/bankRoutes/CheckAccount} with body
 * {@code {"Account": "<account number>"}} — the account number only; the branch is NOT sent
 * (OQ-024), it stays as profile data. The response is {@code {"Response_Code": <code>,
 * "Response_Message": "<text>"}} on HTTP 200, always: the outcome is in the body, never the status.
 * The code set is closed at {@code 1} found / {@code 0} not found / {@code -1} system error
 * (OQ-025).
 *
 * <p>Implementations are selected by configuration, never by a runtime {@code if (mock)} branch —
 * see {@code CoreBankingClientConfiguration}. The stub returns a synthetic code; {@code
 * corebanking.http.HttpCoreBankingClient} calls the real middleware.
 */
public interface CoreBankingClient {

  /**
   * Checks one account against the core banking middleware.
   *
   * <p>Returns the middleware's raw {@code Response_Code} and message inside a {@link
   * CoreBankingCheckResult}, together with the raw request/response bytes when there was an HTTP
   * exchange (for the audit trail). Journey meaning — active, invalid, or an outage — is applied
   * one layer up by {@code AccountCheckService} and {@code AccountCheckOutcome}, never here.
   *
   * @param accountNumber the customer's account number, already trimmed and validated at the web
   *     boundary
   * @return the raw result; {@link CoreBankingCheckResult#code()} is the middleware code verbatim
   * @throws CoreBankingUnavailableException when the middleware produced no usable answer — no
   *     response at all, an empty or non-JSON body, or a {@code Response_Code} that is absent, null
   *     or not a number. A {@code -1} "System Error" code is also surfaced this way by the caller,
   *     not here.
   */
  CoreBankingCheckResult check(String accountNumber);
}
