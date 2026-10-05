package com.sfbank.bayanati.salarycertificate.web;

import com.sfbank.bayanati.salarycertificate.domain.ProfileNotEditableException;
import com.sfbank.bayanati.salarycertificate.domain.SalaryCertificateRejectedException;
import com.sfbank.bayanati.salarycertificate.domain.UnknownProfileException;
import com.sfbank.bayanati.salarycertificate.service.SalaryCertificateService;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stage 6's optional attachment endpoint (docs/journeys/customer.md, BL-022). */
@RestController
@RequestMapping("/api/v1/salary-certificate")
public class SalaryCertificateController {

  /**
   * Base64 inflates by ~4/3 — sized generously above {@code SalaryCertificateService.MAX_BYTES} (10
   * MB) so the service's own size check, not this one, is what actually rejects an oversized
   * certificate.
   */
  public static final int MAX_BASE64_LENGTH = 16_000_000;

  static final int MAX_CODE_LENGTH = 64;

  private final SalaryCertificateService salaryCertificateService;

  public SalaryCertificateController(SalaryCertificateService salaryCertificateService) {
    this.salaryCertificateService = salaryCertificateService;
  }

  @PostMapping
  public AckResponse submit(@RequestBody SalaryCertificateRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    String contentType = clean(request.contentType(), "contentType", MAX_CODE_LENGTH);
    String contentBase64 = clean(request.contentBase64(), "contentBase64", MAX_BASE64_LENGTH);

    byte[] content;
    try {
      content = Base64.getDecoder().decode(contentBase64);
    } catch (IllegalArgumentException malformed) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "contentBase64 is not valid base64");
    }

    try {
      salaryCertificateService.submitCertificate(profileId, contentType, content);
    } catch (UnknownProfileException unknownProfile) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknownProfile.getMessage());
    } catch (ProfileNotEditableException notEditable) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, notEditable.getMessage());
    } catch (SalaryCertificateRejectedException rejected) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejected.getMessage());
    }
    return new AckResponse(profileId.toString());
  }

  private static UUID parseProfileId(String value) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is not a valid UUID");
    }
  }

  private static String clean(String value, String fieldName, int maxLength) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    if (trimmed.length() > maxLength) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " is longer than " + maxLength + " characters");
    }
    for (int i = 0; i < trimmed.length(); i++) {
      if (Character.isISOControl(trimmed.charAt(i))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, fieldName + " contains a control character");
      }
    }
    return trimmed;
  }
}
