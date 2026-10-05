package com.sfbank.bayanati.printedform.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * One value as it will appear on the form: the text, where it came from, and whether it must be
 * laid out left-to-right inside the RTL page.
 *
 * <h2>An absent value has no source, and this type enforces that</h2>
 *
 * <p>Product-owner correction, 2026-09-14 (S8-33). The first draft gave {@link #absent} an origin
 * and printed it as a tagged row — «غير متاح — السجل المدني». That is not logical: a value cannot
 * be unavailable AND attributed to a passport, to the registry, or to the customer's own data
 * entry. <strong>Absence prints «غير متاح» and nothing else.</strong>
 *
 * <p>The invariant is structural rather than a rule the renderer has to remember: {@code origin} is
 * empty if and only if the text is the absence placeholder. There is no way to construct a tagged
 * absent value, so no later caller can reintroduce the thing that was rejected.
 *
 * <p><strong>Which source a field takes is the assembler's job, and it is now a fixed rule rather
 * than a reconciliation.</strong> An earlier draft had two sources share a row and worried about
 * how to compare them. The product owner removed the problem on 2026-09-14 by ruling one source per
 * field — the Civil Registry for fields 5, 6, 7, 9 and 21, the customer's own entry for 4, 18, 23,
 * 43 and 35-41 — so the assembler picks, and nothing here has to reconcile anything.
 *
 * @param text what is printed. Never null and never blank — {@link #absent()} is how absence is
 *     said, because ticket 04 decision 7 requires «غير متاح» rather than an empty cell.
 * @param origin where the value came from, EMPTY for an absent one. <strong>Not printed beside the
 *     value any more</strong>: with one source per field there is never a second value to tell it
 *     apart from. It is kept because the assembler needs it to know which source to read, and
 *     because {@code ofScannedDocument} still maps a scan to the document that produced it.
 * @param latinScript true when the renderer must wrap this value in an LTR embedding. See {@link
 *     #latin} for why this is carried per value rather than guessed from the characters.
 */
public record PrintedValue(String text, Optional<FieldOrigin> origin, boolean latinScript) {

  /** What an absent value prints as. Ticket 04 decision 7: never a blank. */
  public static final String ABSENT_TEXT = "غير متاح";

  public PrintedValue {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(origin, "origin");
    if (text.isBlank()) {
      throw new IllegalArgumentException(
          "a printed value is never blank; use PrintedValue.absent() so the form says «"
              + ABSENT_TEXT
              + "» instead. A blank cell beside a customer value reads as \"the registry agreed\","
              + " which is the one misreading this form must not invite (ticket 04 decision 7).");
    }
    if (ABSENT_TEXT.equals(text) == origin.isPresent()) {
      throw new IllegalArgumentException(
          "an absent value carries no origin, and a present one must carry one; got «"
              + text
              + "» with origin "
              + origin
              + ". Attributing an absence to a source prints «"
              + ABSENT_TEXT
              + " — <source>», which claims the value is both unavailable and read from somewhere"
              + " (product-owner correction, 2026-09-14).");
    }
  }

  /** An Arabic-script value: a name, a city, a reference-list label. */
  public static PrintedValue arabic(String text, FieldOrigin origin) {
    return of(text, origin, false);
  }

  /**
   * A value that must render left-to-right: an account number, an E.164 phone number, a {@code
   * SFB-}/{@code FRU-} reference, a date, a document number, a Latin name off the MRZ.
   *
   * <p><strong>Carried explicitly rather than sniffed from the characters.</strong> Guessing by
   * scanning for Latin letters or digits would get Arabic-Indic digits wrong, would flip a
   * mixed-script employer name on whichever character happened to come first, and would make the
   * form's layout depend on a customer's choice of keyboard. The assembler knows which field it is
   * holding; the renderer should not have to re-derive it.
   */
  public static PrintedValue latin(String text, FieldOrigin origin) {
    return of(text, origin, true);
  }

  /**
   * A value a source DID supply — unless the thing it supplied is, literally, «غير متاح».
   *
   * <p>That is not a hypothetical. Ethnicity (field 10), employer (27) and four address levels
   * (31-34) are free Arabic text, and a customer who has nothing to put there may well type the
   * words "not available". Under the invariant above, attributing that to the customer would be a
   * tagged absence, and the constructor would refuse it — failing the whole form's render over one
   * field. Normalising is the honest reading anyway: a customer who writes «غير متاح» is telling us
   * the value is not available, which is exactly what {@link #absent()} says.
   */
  private static PrintedValue of(String text, FieldOrigin origin, boolean latinScript) {
    Objects.requireNonNull(text, "text");
    return ABSENT_TEXT.equals(text.trim())
        ? absent()
        : new PrintedValue(text, Optional.of(origin), latinScript);
  }

  /** The absent value: «غير متاح», attributed to nobody. */
  public static PrintedValue absent() {
    return new PrintedValue(ABSENT_TEXT, Optional.empty(), false);
  }

  /** Whether this is the «غير متاح» placeholder rather than real data. */
  public boolean isAbsent() {
    return origin.isEmpty();
  }
}
