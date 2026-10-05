package com.sfbank.bayanati.corebanking.domain;

/**
 * The core banking middleware produced no usable answer to a {@code CheckAccount} call.
 *
 * <p>Raised for every case where the journey cannot learn whether the account exists: no response
 * at all (connection refused, connect/read timeout, TLS or DNS failure), an empty or non-JSON body,
 * or a {@code Response_Code} that is absent, null, or not a number. The middleware's own {@code -1}
 * "System Error" is surfaced this way too, by {@code AccountCheckService} rather than by the
 * adapter.
 *
 * <p>Distinct from an unrecognised-but-parseable code (e.g. {@code 7}), which stays an {@code
 * IllegalArgumentException} from {@code AccountCheckOutcome.fromResultCode} — that is a defect in
 * our understanding of the contract, a 500, not a transient a customer should retry.
 *
 * <p>Carries the raw exchange when any bytes were received, so {@code AccountCheckService} can
 * still store the {@code omni_check_response} artifact on the failure path. The web layer maps this
 * to HTTP 503 (BL-008).
 */
public class CoreBankingUnavailableException extends RuntimeException {

  private final transient RawExchange exchange;

  public CoreBankingUnavailableException(String message, RawExchange exchange) {
    super(message);
    this.exchange = exchange;
  }

  public CoreBankingUnavailableException(String message, RawExchange exchange, Throwable cause) {
    super(message, cause);
    this.exchange = exchange;
  }

  /** The raw HTTP exchange, or null when nothing came back (no response, or a stub). */
  public RawExchange exchange() {
    return exchange;
  }
}
