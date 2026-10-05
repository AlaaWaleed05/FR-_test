package com.sfbank.bayanati.corebanking.domain;

/**
 * One core-banking {@code CheckAccount} result, as the port returns it.
 *
 * <p>Carries the middleware's raw {@code Response_Code} and {@code Response_Message} verbatim, plus
 * the raw HTTP exchange for the audit trail. This replaces the old {@code int} return, which could
 * carry neither the message nor the bytes the audit artifacts need. Journey meaning is applied by
 * {@code AccountCheckOutcome.fromResultCode(code)} one layer up.
 *
 * @param code the middleware {@code Response_Code}, verbatim: {@code 1} found, {@code 0} not found,
 *     {@code -1} system error (OQ-025), or — should the contract ever widen — any other integer,
 *     which the mapping layer fails closed on
 * @param message the middleware {@code Response_Message}, verbatim; may be null
 * @param exchange the raw request/response bytes, or null from the stub (no real exchange)
 */
public record CoreBankingCheckResult(int code, String message, RawExchange exchange) {

  /** {@code Response_Code} for an account that exists and is active. The journey proceeds. */
  public static final int FOUND = 1;

  /**
   * {@code Response_Code} for an account the middleware does not recognise. The customer retries.
   */
  public static final int NOT_FOUND = 0;

  /**
   * {@code Response_Code} for the middleware's own "System Error". Not a journey outcome: the
   * caller treats it as an outage (HTTP 503), never as "account not found".
   */
  public static final int SYSTEM_ERROR = -1;

  /** A stub result with no raw exchange to preserve. */
  public static CoreBankingCheckResult withoutExchange(int code) {
    return new CoreBankingCheckResult(code, null, null);
  }
}
