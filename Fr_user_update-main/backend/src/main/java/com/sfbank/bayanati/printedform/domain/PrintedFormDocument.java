package com.sfbank.bayanati.printedform.domain;

import java.util.List;
import java.util.Objects;

/**
 * Everything the form says, resolved and ready to lay out. The renderer turns this into XSL-FO and
 * nothing else; every decision about WHAT is printed has already been taken by the time one of
 * these exists.
 *
 * <p>That split is deliberate and it is what CLAUDE.md's package rule asks for: this type and the
 * code that builds it are business logic a plain JUnit test can exercise with no Spring context, no
 * database, no network and no clock. The FOP wiring that consumes it is plumbing.
 *
 * @param customerName the customer, named once on the compact identity line under the header
 * @param referenceNumber the {@code SFB-}/{@code FRU-} reference. Latin, and since S9-03 it is on
 *     EVERY page twice — the header's end column and the footer's first part — because the approved
 *     artboards put it in both.
 *     <p><strong>That redundancy is load-bearing, not decoration.</strong> Before S9-03 the
 *     reference appeared in exactly two places, the identity band and the continuation header, and
 *     AD-022 ruling (a) plus the single-master collapse deleted both. A filed bank document that
 *     cannot be matched back to its profile is the one failure this form must not have, so the
 *     header and footer each carry it independently.
 *     <p>There is deliberately NO separate {@code accountNumber} component. Ticket 04 decision 5
 *     (no redaction — a redacted form cannot serve as a branch filing document) is satisfied by
 *     field 3 in the body, which is where field-provenance.md puts the account number and where the
 *     artboards print it. An earlier draft carried one here, documented as the thing that proved
 *     decision 5, and no code read it; the test that claimed to prove the decision was in fact
 *     matching field 3's value.
 * @param submittedAt when the customer submitted from the mobile app, already formatted. Formatting
 *     happens outside this type because it needs a zone and a locale, and a domain object that
 *     takes a clock or a zone is the thing the package rule exists to prevent.
 * @param printedBy the operator doing the printing, named on every form. This was already true of
 *     both variants before AD-022 collapsed them (the unattributed one withheld who entered each
 *     FIELD, never who printed the document), so one form loses no printer attribution.
 * @param printedAt when, already formatted, date and time.
 * @param printedOn the same moment as {@code printedAt}, DATE ONLY, for the header's «التاريخ».
 *     Formatted alongside it rather than sliced off it here: splitting a formatted string would
 *     make this type depend on the other one's pattern, and a domain object that parses its own
 *     input is the shape the package rule exists to prevent.
 * @param verificationChips the two lines the approved page 1 sets above the tiles — liveness and
 *     MRZ. A list rather than two components because the writer draws them as a row and neither
 *     chip means anything the other does not; EMPTY is legal and prints nothing.
 * @param sections the body, in field-provenance.md's section order
 * @param images the five images (ticket 04 decision 4 as reversed 2026-09-14): document front,
 *     document portrait, registry portrait, liveness frame, signature.
 * @param includeAttachments the operator's answer to the one print-time question. When true the
 *     five images print AGAIN, full size, one per page after the body, and a PDF salary certificate
 *     is appended on its own page. Asked every time, defaulting to no.
 * @param salaryCertificate the customer's income evidence, or null. Printed only when {@code
 *     includeAttachments} is set.
 */
public record PrintedFormDocument(
    String customerName,
    String referenceNumber,
    String submittedAt,
    String printedBy,
    String printedAt,
    String printedOn,
    List<PrintedChip> verificationChips,
    List<PrintedSection> sections,
    List<PrintedImage> images,
    boolean includeAttachments,
    SalaryCertificate salaryCertificate) {

  /** The form's title, as the artboards set it. */
  public static final String TITLE = "استمارة تحديث البيانات";

  // BANK_NAME_EN was HERE and is deleted, not left unused. Its only reader was the old SFB
  // header's second line, which set «SUDANESE FRENCH BANK» in Latin under the Arabic name; the
  // approved header has no such line. The bank's English name is not printed on this form at all.

  // BANK_NAME_AR and INTERNAL_USE_NOTICE were HERE and are deleted at S9-06, same precedent. They
  // were the two halves of the header's muted line, «البنك السوداني الفرنسي — للاستخدام الداخلي»,
  // and that line is gone: the bank's name is now drawn in its logo's own calligraphy as an IMAGE
  // (brand/sfb-wordmark-ar-navy.png), which no typeface can reproduce, and the internal-use notice
  // is not printed at all. Both by product-owner ruling, AD-022 (h), 2026-09-18.
  //
  // On decision 7, because the comment deleted here argued the opposite and the next reader
  // deserves the correction: ticket 05 decision 7 says the form is INTERNAL and is never handed to
  // the customer. That is a statement about process. It never required the notice to be PRINTED —
  // "a document that says so on its face is harder to hand over by accident" was this file's own
  // gloss, not the decision. Dropping the line leaves decision 7 intact. What it departs from is
  // the artboard, which is why it needed a ruling.
  //
  // The bank's name still reaches customers in the message renderers, which each hold their own
  // BANK_NAME_AR — a different context (SMS, WhatsApp, email), deliberately not shared with a
  // constant about what a PDF header draws.

  /**
   * The mobile app's name, printed in the header beside the bank's wordmark. AD-022 (i).
   *
   * <p><strong>The tatweel spelling is deliberate and must not be "corrected".</strong> This is
   * «بياناتي» written with U+0640 ARABIC TATWEEL twice after each of ي، ن، ت — 13 codepoints where
   * the plain spelling has 7 — and it is character-for-character what the mobile splash screen sets
   * ({@code launch_screen.dart}). The elongation is what makes the name read as the app's own mark
   * rather than as a word in a sentence, and the form is printing the app's mark.
   *
   * <p>Tatweels are invisible to anyone reading this file, so {@code PrintedFormDocumentTest}
   * asserts the codepoint sequence rather than trusting the literal to survive a tidy-up.
   *
   * <p>It is set in Amiri, the splash's face — the one span on this form that is not IBM Plex Sans
   * Arabic. See {@code fop/fop.xconf} on why that face is registered Regular-only and why the span
   * pins its own weight.
   */
  public static final String APP_NAME_AR = "بيــانــاتــي";

  /** The product, named in the footer's first part exactly as the approved artboards set it. */
  public static final String PRODUCT_NAME = "AZ Omni eKYC";

  /** «طبع بواسطة الموظف: », the footer's middle part and AD-022 ruling 2's whole justification. */
  public static final String PRINTED_BY_LABEL = "طبع بواسطة الموظف: ";

  /** The header's date label. */
  public static final String DATE_LABEL = "التاريخ ";

  public PrintedFormDocument {
    sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
    images = List.copyOf(Objects.requireNonNull(images, "images"));
    verificationChips = List.copyOf(Objects.requireNonNull(verificationChips, "verificationChips"));
  }

  /**
   * Never the field values. Every record here is otherwise a default-{@code toString} carrier of a
   * customer's name and every value on their form, and this is the aggregate an error handler
   * reaches for. What identifies a form in a log line is which form it is and whose — by reference,
   * not by content.
   */
  @Override
  public String toString() {
    return "PrintedFormDocument["
        + referenceNumber
        + ", "
        + sections.size()
        + " sections, "
        + images.size()
        + " images, attachments="
        + includeAttachments
        + "]";
  }

  /**
   * The salary certificate, carried as bytes plus its declared media type.
   *
   * <p><strong>The type matters here, unlike on {@link PrintedImage}.</strong> FOP works out an
   * image's format from its bytes, so an image needs no declared type. The certificate is the one
   * artifact that may be {@code application/pdf} — the only kind whose content-type allow-list
   * admits one — and FOP cannot lay an existing PDF page out at all. So the renderer has to know
   * which it is holding: an image goes through the normal attachment page, a PDF is appended to the
   * finished document instead.
   *
   * <p><strong>There is no separate FILE.</strong> Product-owner ruling 2026-09-14: a printed
   * certificate is a separate PAGE. That supersedes ticket 05 decision 9's "two separate
   * documents", and it is the reason PDFBox is a main-scope dependency.
   */
  public record SalaryCertificate(byte[] bytes, String contentType) {

    public static final String PDF = "application/pdf";

    public SalaryCertificate {
      Objects.requireNonNull(bytes, "bytes");
      Objects.requireNonNull(contentType, "contentType");
      bytes = bytes.clone();
    }

    /**
     * Whether the certificate is a PDF, ignoring any content-type PARAMETERS.
     *
     * <p>A stored {@code content_type} can read {@code application/pdf; charset=x} — {@code
     * OperatorImagePolicy.pinContentType} exists for that reason and strips parameters the same
     * way. An exact match would leave such a row failing this test, and a failed test here means
     * the certificate is silently not printed at all rather than printed wrongly.
     */
    public boolean isPdf() {
      int parameters = contentType.indexOf(';');
      String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
      return PDF.equalsIgnoreCase(type.trim());
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    /** Never the bytes: this is a customer's pay document, and a toString reaches logs. */
    @Override
    public String toString() {
      return "SalaryCertificate[" + bytes.length + " bytes, " + contentType + "]";
    }
  }
}
