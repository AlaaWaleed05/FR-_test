package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.operator.service.OperatorProfileViewService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** operator.md's "single profile view". */
@RestController
@RequestMapping("/api/v1/operator/profiles")
public class ProfileController {

  private final OperatorProfileViewService profileViewService;

  public ProfileController(OperatorProfileViewService profileViewService) {
    this.profileViewService = profileViewService;
  }

  @GetMapping("/{profileId}")
  public ProfileDetailResponse view(OperatorIdentity identity, @PathVariable String profileId) {
    UUID id = parseProfileId(profileId);
    try {
      ProfileDetail detail = profileViewService.view(identity, id);
      return ProfileDetailResponse.from(detail);
    } catch (UnknownProfileException unknown) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage());
    }
  }

  private static UUID parseProfileId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is not a valid UUID");
    }
  }
}
