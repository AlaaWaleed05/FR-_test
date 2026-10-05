package com.sfbank.bayanati.civilregistry.domain;

/**
 * The Civil Registry produced no response at all — a connect or read timeout, a refused connection,
 * a TLS or DNS failure. customer.md Stage 9: "Civil Registry unreachable or returning nothing ...
 * The session pauses. It does not fail." — the caller pauses the profile (status {@code
 * awaiting_registry}) rather than treating this as a hard failure.
 *
 * <p>This is the only outcome that is <em>not</em> a {@link RegistryLookup}: a response of any
 * kind, however malformed, is classified {@code not_found} there (AD-002b).
 *
 * <p>Carries the request-only exchange when a real call was attempted, so the {@code
 * civil_registry_request} artifact can still be stored; null from the stub.
 */
public class RegistryUnreachableException extends RuntimeException {

  private final transient RegistryExchange exchange;

  public RegistryUnreachableException(String message) {
    this(message, null);
  }

  public RegistryUnreachableException(String message, RegistryExchange exchange) {
    super(message);
    this.exchange = exchange;
  }

  public RegistryUnreachableException(String message, RegistryExchange exchange, Throwable cause) {
    super(message, cause);
    this.exchange = exchange;
  }

  /** The request bytes that were sent, with no response part; null when there was no real call. */
  public RegistryExchange exchange() {
    return exchange;
  }
}
