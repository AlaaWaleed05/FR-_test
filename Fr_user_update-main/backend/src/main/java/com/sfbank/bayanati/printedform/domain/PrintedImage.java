package com.sfbank.bayanati.printedform.domain;

import java.util.Objects;

/**
 * One image in the printed bundle. FIVE of them, product-owner ruling 2026-09-14: the identity
 * document's front page, the portrait off that document, the Civil Registry's photograph, the
 * liveness frame, and the signature. FOUR get a tile on the form — the liveness frame is in the
 * bundle and off the page (AD-022), which is what {@code onPage} carries.
 *
 * <h2>The document scan is on the form now, and that is a reversal</h2>
 *
 * <p>Ticket 04 decision 4 excluded document scans, citing {@code operator.md}'s rule that "a file
 * of passport scans leaving the system as an email attachment is the highest-risk artifact this
 * product could produce". Worth being accurate about what that rule covers: it sits under {@code
 * operator.md}'s <em>Export</em> heading and governs the bulk XLSX/CSV list — many customers' scans
 * in one emailed file. Decision 4 applied it to a single filed form by analogy, and the product
 * owner has now reversed the analogy. It does not contradict {@code operator.md}; it does put a
 * document scan on the filed form, which is a deliberate choice rather than an oversight.
 *
 * <p>The consequence ticket 04 flagged and ticket 05 carried is only sharper: the filed form bears
 * a face and a document, so it is a PII artifact in its own right.
 *
 * <h2>No image is labelled by origin</h2>
 *
 * <p>An earlier version printed the origin beside the caption, because item 52 asks for both
 * portraits to be labelled and, when they shared one caption, the label was the only thing telling
 * them apart. With five distinct captions it stops earning its place and starts actively lying: the
 * registry portrait's caption «السجل المدني» IS {@code CIVIL_REGISTRY}'s tag, so it printed «السجل
 * المدني — السجل المدني»; and the liveness frame would have printed «إثبات الحياة — جواز سفر»,
 * attributing a selfie to the passport. Same reasoning that took the label off the signature in the
 * previous round.
 *
 * @param caption the Arabic caption printed beneath the box. Taken from the back office's own
 *     captions ({@code backoffice/src/profiles/artifactTiles.ts}) so screen and paper agree.
 * @param origin which document or party this image came from. Not printed; kept because the
 *     assembler reads it and because a later reader needs to know what a box holds.
 * @param bytes the image, or null when it is absent. Null is the ORDINARY case on a manually
 *     completed profile, which by design has no scan, no registry lookup and no artifacts at all —
 *     so this type must represent absence rather than assume presence.
 *     <p>There is deliberately no {@code contentType}: an earlier draft carried and validated one,
 *     and nothing read it. FOP determines an image's format from its bytes, not from a declared
 *     type, so a media type here would have been a field that looked load-bearing and was not.
 * @param onPage whether the image gets a tile in the form's own image grid. False for the liveness
 *     frame alone, which the approved page 1 does not show but which still rides as an appended
 *     sheet — see {@link #attachmentOnly()}.
 */
public record PrintedImage(String caption, FieldOrigin origin, byte[] bytes, boolean onPage) {

  public PrintedImage {
    Objects.requireNonNull(caption, "caption");
    Objects.requireNonNull(origin, "origin");
    bytes = bytes == null ? null : bytes.clone();
  }

  /** The front page of the scanned identity document. */
  public static PrintedImage documentScan(String caption, FieldOrigin origin, byte[] bytes) {
    return new PrintedImage(caption, origin, bytes, true);
  }

  /**
   * A face image: the portrait off the document, the registry's photograph, or the liveness frame.
   */
  public static PrintedImage portrait(String caption, FieldOrigin origin, byte[] bytes) {
    return new PrintedImage(caption, origin, bytes, true);
  }

  /** The customer's signature. */
  public static PrintedImage signature(String caption, byte[] bytes) {
    return new PrintedImage(caption, FieldOrigin.CUSTOMER, bytes, true);
  }

  /** An image the profile does not have. Prints «غير متاح» in its place on the form. */
  public static PrintedImage absent(String caption, FieldOrigin origin) {
    return new PrintedImage(caption, origin, null, true);
  }

  /**
   * The same image, kept in the bundle but off the page-1 grid.
   *
   * <p>AD-022 with the approved artboards: page 1 shows FOUR tiles and the liveness frame is not
   * one of them, while the frame itself is still captured, still stored and still printed as its
   * own appended sheet when an operator asks for attachments. Suppressing it by dropping it from
   * {@link PrintedFormDocument#images()} would have taken the attachment with it, silently, because
   * {@code FoDocumentWriter.attachments} walks the same list.
   */
  public PrintedImage attachmentOnly() {
    return new PrintedImage(caption, origin, bytes, false);
  }

  public boolean isPresent() {
    return bytes != null;
  }

  @Override
  public byte[] bytes() {
    return bytes == null ? null : bytes.clone();
  }

  /**
   * Records do not deep-compare arrays, and a defensive copy makes the generated {@code equals}
   * false for two images holding identical bytes — which is exactly what a test asserting "the
   * assembler produced this image" would compare. Overridden so the type behaves the way its
   * callers reasonably expect.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof PrintedImage that
        && caption.equals(that.caption)
        && origin == that.origin
        && onPage == that.onPage
        && java.util.Arrays.equals(bytes, that.bytes);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(caption, origin, onPage, java.util.Arrays.hashCode(bytes));
  }

  @Override
  public String toString() {
    // Never the bytes: this is a customer's face, and a toString reaches logs.
    return "PrintedImage["
        + caption
        + ", "
        + origin
        + ", "
        + (isPresent() ? bytes.length + " bytes" : "absent")
        + "]";
  }
}
