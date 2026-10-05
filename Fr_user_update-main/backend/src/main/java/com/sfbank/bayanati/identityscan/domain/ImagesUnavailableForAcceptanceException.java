package com.sfbank.bayanati.identityscan.domain;

/**
 * The JWS verified, but at least one image is already gone (uqudo-sdk.md: "Images are kept only for
 * the duration of the session, 30 minutes"). R-012/R-021's ordering rule: <strong>the scan is NOT
 * accepted</strong>. Does not count against the stage 8 retry budget — this is a system-timing
 * fact, not a scan-quality problem.
 */
public class ImagesUnavailableForAcceptanceException extends RuntimeException {

  public ImagesUnavailableForAcceptanceException(String message) {
    super(message);
  }
}
