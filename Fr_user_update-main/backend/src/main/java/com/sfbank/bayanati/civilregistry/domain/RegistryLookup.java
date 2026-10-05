package com.sfbank.bayanati.civilregistry.domain;

/**
 * One Civil Registry lookup as the port returns it — the registry answered, and this is what the
 * answer classified to under AD-002b's coarse rule (docs/components/civil-registry.md, Decisions).
 *
 * <p>The registry <em>not</em> answering is not a value of this type: that is {@link
 * RegistryUnreachableException}. So a {@code RegistryLookup} is always one of exactly two things —
 * a matching populated record ({@link #found()} true, journey state {@code ok}) or "invalid / not
 * found" ({@link #found()} false, journey state {@code not_found}), with {@link #reason()} saying
 * which rule of the classification fired.
 *
 * @param record the parsed record, present only when the registry returned a 2xx JSON object whose
 *     {@code IDENTITY_NUMBER} equals the number sent; null otherwise
 * @param identityNumberReturned the {@code IDENTITY_NUMBER} the registry returned, when a string
 *     value was present at all — on {@link #MATCHED} and, more importantly, on {@link
 *     #IDENTITY_NUMBER_MISMATCH}, so the guard's evidence is auditable ({@code
 *     app.registry_result.identity_number_returned}, BL-030); null when nothing parseable came back
 * @param reason one of the reason-code constants on this class, naming the classification rule that
 *     fired; never carries a value from the response. Null from the stub, which classifies nothing
 * @param exchange the raw request/response bytes for the audit artifacts, or null from the stub
 */
public record RegistryLookup(
    RegistryLookupResult record,
    String identityNumberReturned,
    String reason,
    RegistryExchange exchange) {

  /**
   * 2xx, JSON object, {@code IDENTITY_NUMBER} a string equal (after {@code strip()}) to the number
   * sent.
   */
  public static final String MATCHED = "matched";

  /** Any non-2xx status — the observed HTTP 400 GlassFish page lands here, before any parsing. */
  public static final String NON_SUCCESS_STATUS = "non_success_status";

  /** 2xx with a zero-length body (includes 204). */
  public static final String EMPTY_BODY = "empty_body";

  /** 2xx but the body is not JSON. */
  public static final String NON_JSON_BODY = "non_json_body";

  /** JSON, but not an object — a bare {@code null}, array, string or number. */
  public static final String NOT_AN_OBJECT = "not_an_object";

  /** A JSON object with no {@code IDENTITY_NUMBER} key, or the key holding JSON null. */
  public static final String IDENTITY_NUMBER_ABSENT = "identity_number_absent";

  /**
   * {@code IDENTITY_NUMBER} present but not a JSON string. A JSON number is rejected on purpose:
   * the service binds {@code NID} as a string and {@code 0} and {@code 00000000000} are two
   * different records, so a numeric value would already have lost leading zeros before reaching us.
   */
  public static final String IDENTITY_NUMBER_NOT_STRING = "identity_number_not_string";

  /** {@code IDENTITY_NUMBER} is a string that is empty after {@code strip()}. */
  public static final String IDENTITY_NUMBER_EMPTY = "identity_number_empty";

  /** A populated record whose {@code IDENTITY_NUMBER} differs from the number sent. */
  public static final String IDENTITY_NUMBER_MISMATCH = "identity_number_mismatch";

  /** No response at all — carried by {@link RegistryUnreachableException}, never by this record. */
  public static final String NO_RESPONSE = "no_response";

  /** Whether the registry returned this customer's record (journey state {@code ok}). */
  public boolean found() {
    return record != null;
  }

  /** A not-found answer with the reason that produced it. */
  public static RegistryLookup notFound(
      String reason, String identityNumberReturned, RegistryExchange exchange) {
    return new RegistryLookup(null, identityNumberReturned, reason, exchange);
  }

  /** A matching record. */
  public static RegistryLookup matched(RegistryLookupResult record, RegistryExchange exchange) {
    return new RegistryLookup(record, record.identityNumber(), MATCHED, exchange);
  }
}
