package com.sfbank.bayanati.civilregistry.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * The product owner's Q11 rules for the two Civil Registry fields the database constrains
 * (docs/sessions/2026-09-04-civil-registry-decisions.md): make a safe choice covering the
 * possibilities, store raw, never reject.
 *
 * <p>Both rules exist because {@code app.registry_result} would reject anything else — {@code
 * sex_registry char(1) CHECK (sex_registry IN ('m','f'))} and {@code date_of_birth date} (V0023). A
 * pass-through of {@code "male"} or {@code "00/00/0000"} would raise a constraint violation inside
 * {@code submitScan}'s transaction and roll an accepted scan back into a 500. Null is the only
 * value that cannot fail, and the raw value stays recoverable from the byte-identical {@code
 * civil_registry_response} audit artifact — no new columns.
 *
 * <p>Pure functions, no Spring, no clock: this is a {@code domain} package by the CLAUDE.md rule.
 */
public final class RegistryFieldNormaliser {

  /**
   * {@code DD/MM/YYYY} exactly (civil-registry.md: "not ISO 8601"). {@code uuuu}, not {@code yyyy}:
   * under {@link ResolverStyle#STRICT} {@code yyyy} is year-of-era and needs an era field, so every
   * input would fail; {@code uuuu} is the proleptic year. {@link Locale#ROOT} so an Arabic default
   * locale's decimal style can never reject ASCII digits ({@code RegistryLookupResult}: "never a
   * locale default").
   */
  static final DateTimeFormatter BIRTH_DATE =
      DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);

  private RegistryFieldNormaliser() {}

  /**
   * Parses the registry's {@code BIRTH_DATE}. Null — never an exception — for null, blank, a
   * partial date, {@code 00/00/0000}, an impossible date such as 29 February in a common year, ISO
   * input, or single-digit day/month. No range check: the product owner declined to assume formats
   * not observed (Q10), and a plausibility rule would be exactly such an assumption.
   */
  public static LocalDate parseBirthDate(String raw) {
    String value = strip(raw);
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value, BIRTH_DATE);
    } catch (DateTimeParseException unparseable) {
      return null;
    }
  }

  /**
   * Normalises the registry's {@code GENDER}: strip, lowercase in {@link Locale#ROOT}, accept
   * exactly {@code m} or {@code f}. Everything else — {@code male}, {@code M} followed by a stray
   * character, empty, a digit, an Arabic word — is null.
   */
  public static String normaliseGender(String raw) {
    String value = strip(raw);
    if (value == null) {
      return null;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    return "m".equals(lower) || "f".equals(lower) ? lower : null;
  }

  /**
   * The national number reduced to its ASCII digits, for the {@code NID} the registry is asked
   * about and for comparing its answer. Null when the input holds no digit at all.
   *
   * <p><strong>Found live at S7-12, 2026-09-06, on the first real passport ever scanned through
   * this system.</strong> Uqudo transcribes the document faithfully, and a Sudanese passport prints
   * the national number in grouped form — {@code NNN-NNNN-NNNN}, 13 characters carrying 11 digits.
   * The adapter forwarded that verbatim. The registry binds {@code NID} as a string and matches on
   * the bare digits (proven by the five authorised probes of 2026-09-04: lengths 1 and 11 returned
   * populated records, lengths 3, 10 and 12 returned the 400 HTML page), so every hyphenated number
   * came back as the service's not-found answer. The customer was then shown "the service is
   * temporarily unavailable, try again shortly" for a lookup that could never succeed.
   *
   * <p><strong>Why here and not at capture.</strong> {@code app.scan_result.identity_number} must
   * keep what the document actually says — it is evidence, and the audit artifact is byte-identical
   * to the response. Canonicalisation is the adapter's job: speaking the far end's dialect at the
   * boundary, leaving the stored record faithful.
   *
   * <p><strong>Not a validation rule.</strong> It rejects nothing and asserts no length — Q10's
   * decision that the product owner declined to assume a structure still stands. Removing a
   * separator the document itself printed is canonicalisation, not a structural claim.
   */
  public static String digitsOnly(String raw) {
    if (raw == null) {
      return null;
    }
    StringBuilder digits = new StringBuilder(raw.length());
    raw.codePoints()
        .filter(codePoint -> codePoint >= '0' && codePoint <= '9')
        .forEach(codePoint -> digits.append((char) codePoint));
    return digits.isEmpty() ? null : digits.toString();
  }

  /**
   * {@link String#strip()} — not {@link String#trim()} — with empty collapsed to null. {@code
   * strip()} removes every code point {@link Character#isWhitespace} reports, which covers the
   * Unicode space separators {@code trim()} (≤ U+0020 only) misses. Neither removes a no-break
   * space (U+00A0, U+2007, U+202F) or a bidi mark (U+200E/U+200F); that limit is why the adapter's
   * failure log reports whether two values differ only by whitespace.
   */
  public static String strip(String raw) {
    if (raw == null) {
      return null;
    }
    String stripped = raw.strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
