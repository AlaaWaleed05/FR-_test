package com.sfbank.bayanati.printedform.domain;

import java.util.List;

/**
 * The five images the printed bundle carries. Declaration order groups them by what each IS; the
 * order the form LAYS OUT is {@link #inFormOrder()}'s, and since S9-03 the two differ, each with
 * the artifact kind it is read from and the caption printed under it.
 *
 * <p>Five, not three: the product owner REVERSED ticket 04 decision 4 on 2026-09-14 and put the
 * identity document's front page on the form. Decision 4 had excluded document scans by citing
 * {@code operator.md}'s "a file of passport scans … the highest-risk artifact this product could
 * produce" rule, which sits under that document's <strong>Export</strong> heading and governs the
 * bulk XLSX/CSV list — many customers' scans in one emailed file. Applying it to a single filed
 * form was an analogy, and the analogy was reversed.
 *
 * <p><strong>The captions are the back office's own</strong> ({@code
 * backoffice/src/profiles/artifactTiles.ts}'s {@code TILE_CAPTIONS_AR}), so an operator reads the
 * same words on screen and on paper. They are a COPY and no gate holds the two files together — the
 * same honest warning {@code artifactTiles.ts} carries about its own copy of the server's
 * viewable-kind list. Whoever changes a caption changes it in both places.
 *
 * <p><strong>No image carries an origin label</strong>, and that is the same 2026-09-14 ruling.
 * With five distinct captions a tag stopped distinguishing and started repeating or misattributing:
 * the registry portrait's caption IS its origin, so it printed «السجل المدني — السجل المدني», and
 * the liveness frame would have printed «إثبات الحياة — جواز سفر», attributing a selfie to the
 * passport. The {@code origin} each slot still carries is internal bookkeeping — {@link
 * PrintedImage} requires one, and the renderer prints none.
 */
public enum PrintedFormImageSlot {

  /** Field 53. The document's front page — on the form since the 2026-09-14 reversal. */
  DOCUMENT("doc_front", "وثيقة الهوية"),

  /** Field 52, the portrait read off the scanned document. */
  DOCUMENT_PORTRAIT("portrait_uqudo", "صورة الوثيقة"),

  /** Field 52's other half: the Civil Registry's own photograph. */
  REGISTRY_PORTRAIT("portrait_registry", "السجل المدني"),

  /** Field 54, the liveness audit frame. */
  LIVENESS("face_audit_trail", "إثبات الحياة"),

  /** Field 49, the customer's signature — drawn or uploaded, stored byte-identical. */
  SIGNATURE("signature", "التوقيع");

  private final String artifactKind;
  private final String caption;

  PrintedFormImageSlot(String artifactKind, String caption) {
    this.artifactKind = artifactKind;
    this.caption = caption;
  }

  /** The {@code app.artifact_ref.kind} this slot reads from. */
  public String artifactKind() {
    return artifactKind;
  }

  /** The Arabic caption printed under the box, present or absent. */
  public String caption() {
    return caption;
  }

  /**
   * In the approved page 1's own order: the four tiles it shows, then the liveness frame, which it
   * does not.
   *
   * <p>NOT the declaration order, and deliberately so. The declaration is grouped by what each slot
   * IS — document, then the two portraits, then the audit frame, then the signature — while the
   * artboard reads registry portrait, document portrait, document face, signature. Returning an
   * explicit list keeps the two independent: a layout change does not renumber the enum, whose
   * ordinals reach {@code EnumMap} iteration and the artifact-kind list.
   *
   * <p>The liveness frame comes LAST because it is off the page (AD-022) and only ever an appended
   * sheet, so the four tiles are the first four entries and the grid's order is the list's order.
   */
  public static List<PrintedFormImageSlot> inFormOrder() {
    return List.of(REGISTRY_PORTRAIT, DOCUMENT_PORTRAIT, DOCUMENT, SIGNATURE, LIVENESS);
  }

  /** The kinds a print reads, for a repository's {@code kind = ANY (…)} filter. */
  public static List<String> artifactKinds() {
    return inFormOrder().stream().map(PrintedFormImageSlot::artifactKind).toList();
  }
}
