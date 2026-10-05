package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.FieldEditRejectedException;
import com.sfbank.bayanati.operator.domain.FieldNotEditableException;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.operator.service.FieldEditService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * BL-135 / AD-015: the operator edits one customer-entered field.
 *
 * <p>{@code PATCH} rather than {@code PUT} or {@code POST}: this is a partial update of one field
 * on an existing profile, and the URL addresses that field. One field per request, so every edit is
 * its own transaction, its own audit event and its own {@code app.profile_field_edit} row — a batch
 * would have to decide what happens when the third of five fields is refused, and BL-135's "audit
 * records the field name plus old and new values" reads most honestly one event per field anyway.
 *
 * <p>Status mapping mirrors {@code ReviewController}'s: 404 unknown profile, 403 wrong access
 * level, 400 malformed edit, 409 a field this profile does not expose or a status that forbids
 * editing.
 */
@RestController
@RequestMapping("/api/v1/operator/profiles")
public class FieldEditController {

  private final FieldEditService fieldEditService;

  public FieldEditController(FieldEditService fieldEditService) {
    this.fieldEditService = fieldEditService;
  }

  @PatchMapping("/{profileId}/fields/{fieldKey}")
  public FieldEditResponse edit(
      OperatorIdentity identity,
      @PathVariable String profileId,
      @PathVariable String fieldKey,
      @RequestBody FieldEditRequest request) {
    UUID id = parseProfileId(profileId);
    try {
      String stored =
          fieldEditService.edit(identity, id, fieldKey, request == null ? null : request.value());
      return new FieldEditResponse(fieldKey, stored);
    } catch (UnknownProfileException unknown) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage());
    } catch (AccessLevelRequiredException forbidden) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbidden.getMessage());
    } catch (FieldEditRejectedException rejected) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejected.getMessage());
    } catch (FieldNotEditableException notEditable) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, notEditable.getMessage());
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
