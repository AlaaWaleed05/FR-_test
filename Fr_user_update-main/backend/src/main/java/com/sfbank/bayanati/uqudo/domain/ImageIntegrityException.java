package com.sfbank.bayanati.uqudo.domain;

/**
 * The downloaded bytes' own sha256 does not match the checksum Uqudo supplied alongside the image
 * id (docs/components/uqudo-sdk.md: "Verify each against its checksum; a mismatch is a hard
 * failure"). Distinct from {@link ImageUnavailableException} — the image downloaded, but is not
 * trustworthy.
 */
public class ImageIntegrityException extends RuntimeException {

  public ImageIntegrityException(String imageId) {
    super("image " + imageId + " failed checksum verification");
  }
}
