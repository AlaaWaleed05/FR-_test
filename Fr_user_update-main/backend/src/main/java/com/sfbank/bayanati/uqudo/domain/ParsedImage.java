package com.sfbank.bayanati.uqudo.domain;

/**
 * One image reference out of a parsed enrolment result — {@code kind} is one of the exact strings
 * {@code app.artifact_ref_kind_check} (V0008/V0026) accepts, so {@code IdentityScanRepository}
 * writes it straight through with no translation step.
 *
 * @param kind {@link #DOC_FRONT}, {@link #DOC_BACK}, {@link #DOC_FRONT_FRAME}, {@link
 *     #DOC_BACK_FRAME} or {@link #PORTRAIT_UQUDO}
 * @param uqudoImageId the id to pass to {@link UqudoClient#downloadImage}
 * @param checksum Uqudo's own {@code "sha256:<digest>"} string, verified against the downloaded
 *     bytes before acceptance (uqudo-sdk.md: "a mismatch is a hard failure")
 */
public record ParsedImage(String kind, String uqudoImageId, String checksum) {

  public static final String DOC_FRONT = "doc_front";
  public static final String DOC_BACK = "doc_back";
  public static final String DOC_FRONT_FRAME = "doc_front_frame";
  public static final String DOC_BACK_FRAME = "doc_back_frame";
  public static final String PORTRAIT_UQUDO = "portrait_uqudo";
}
