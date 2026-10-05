package com.sfbank.bayanati.uqudo.domain;

/**
 * Uqudo no longer has this image — the observed 404/410 case (docs/components/uqudo-sdk.md: "Images
 * are kept only for the duration of the session, 30 minutes"). A verified JWS whose images are gone
 * is NOT accepted (R-012/R-021's ordering rule) — this exception does not count against the stage 8
 * retry budget, since it is a system-timing fact, not a scan-quality problem.
 */
public class ImageUnavailableException extends RuntimeException {

  public ImageUnavailableException(String imageId) {
    super("image " + imageId + " is no longer available");
  }

  /**
   * The same outcome reached for a reason other than retention — an unexpected status, or a
   * transport failure ({@code HttpUqudoClient.downloadImage}). The journey treats these alike (the
   * bytes are not available, and that is not the customer's scan quality), but the audit payload's
   * {@code reason} carries this message, so the trail still records which it actually was.
   *
   * @param reason names the cause. Never a payload value, a URL or a credential — an exception
   *     class name or an HTTP status.
   */
  public ImageUnavailableException(String imageId, String reason) {
    super("image " + imageId + " is not available: " + reason);
  }
}
