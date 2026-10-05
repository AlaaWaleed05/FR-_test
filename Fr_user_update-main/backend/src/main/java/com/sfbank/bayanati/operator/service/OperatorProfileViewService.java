package com.sfbank.bayanati.operator.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.operator.domain.EditabilityFacts;
import com.sfbank.bayanati.operator.domain.EditableField;
import com.sfbank.bayanati.operator.domain.EditableFieldPolicy;
import com.sfbank.bayanati.operator.domain.OperatorAuditPayload;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.ProfileViewRepository;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single profile view — operator.md "The single profile view" section. "Opening a profile is an
 * audit event" — every call writes {@code profile_viewed} on the profile's own chain, which already
 * exists from Stage 1b onward (no {@code ensure} call needed here, mirroring how every
 * profile-scoped writer past Stage 1b never re-calls {@code ProfileRepository#ensureAuditChain}).
 *
 * <p><strong>This is also where {@code editableFields} is derived</strong> (BL-135, AD-015). The
 * repository returns the field empty, because {@code EditableFieldPolicy} is business logic and a
 * {@code jdbc} package is plumbing. Reading is not a privileged act here: the set is identical for
 * a viewer and an operator, because it describes the PROFILE, not the caller — whether the caller
 * may act on it is {@code FieldEditService}'s access-level check and the {@code PATCH} route's own
 * OPERATOR rule. A viewer seeing which fields are editable tells them nothing they cannot already
 * read off the profile.
 */
@Service
public class OperatorProfileViewService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_PROFILE_VIEWED = "profile_viewed";

  static final String LIST_ADMIN_DIVISION = "admin_division";

  private final ProfileViewRepository profileViewRepository;
  private final AuditEventWriter auditEventWriter;
  private final ReferenceCatalog referenceCatalog;

  public OperatorProfileViewService(
      ProfileViewRepository profileViewRepository,
      AuditEventWriter auditEventWriter,
      ReferenceCatalog referenceCatalog) {
    this.profileViewRepository = profileViewRepository;
    this.auditEventWriter = auditEventWriter;
    this.referenceCatalog = referenceCatalog;
  }

  public ProfileDetail view(OperatorIdentity identity, UUID profileId) {
    ProfileDetail raw =
        profileViewRepository
            .find(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));

    auditEventWriter.append(
        new AuditEvent(
            CHAIN_KIND,
            profileId.toString(),
            EVENT_PROFILE_VIEWED,
            "operator",
            identity.operatorId(),
            profileId,
            null,
            UUID.randomUUID(),
            CanonicalJson.object(OperatorAuditPayload.withActorRole(identity, Map.of()))));

    return raw.withEditableFields(editableFields(raw));
  }

  /**
   * The derived editable set, as wire strings.
   *
   * <p>{@code currentRootItemCode} is read rather than a hardcoded {@code 'SD'} — the same lookup
   * {@code DataEntryService} and {@code FieldEditService} both use, so all three agree on which
   * country has list-picked administrative divisions. An absent root yields an EMPTY set rather
   * than an exception: a misconfigured reference catalogue must not make a profile unviewable, and
   * the honest consequence is that no «تعديل» chip is offered until it is fixed. The write path
   * throws on the same condition, so nothing becomes editable by accident.
   */
  private List<String> editableFields(ProfileDetail detail) {
    // The STATUS gate first, and it must match FieldEditService.EDITABLE_STATUSES exactly. The
    // derivation below answers "which fields COULD be keyed on a profile shaped like this"; it
    // says nothing about whether this profile may be edited at all right now. Without this, an
    // approved profile came back with a full editable list and the browser would draw «تعديل»
    // chips on a screen where AD-015 says editing has stopped for ever -- every one of them a
    // 409. Found by @agent-reviewer.
    if (!FieldEditService.EDITABLE_STATUSES.contains(detail.status())) {
      return List.of();
    }
    return referenceCatalog
        .currentRootItemCode(LIST_ADMIN_DIVISION)
        .map(
            sudanCode ->
                EditableFieldPolicy.editableFor(
                        EditabilityFacts.from(detail.customerData(), detail.scanResult()),
                        sudanCode)
                    .stream()
                    .map(EditableField::name)
                    .sorted()
                    .toList())
        .orElseGet(List::of);
  }
}
