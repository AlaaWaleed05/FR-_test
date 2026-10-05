package com.sfbank.bayanati.operator.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.operator.domain.OperatorAuditPayload;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileListFilter;
import com.sfbank.bayanati.operator.domain.ProfileListPage;
import com.sfbank.bayanati.operator.domain.ProfileListRepository;
import com.sfbank.bayanati.operator.domain.ProfileListResult;
import com.sfbank.bayanati.operator.domain.ProfileListSortField;
import com.sfbank.bayanati.operator.domain.SortOrder;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The profile list — operator.md: search/filter across any field or combination, server-side
 * (AD-006, BL-015). Every call is its own audit event on the calling operator's own {@code
 * operator} chain (docs/components/backoffice-components.md: "operator.md requires 'search and
 * filter executed' as an audit event, which a client-side filter cannot produce").
 */
@Service
public class OperatorProfileListService {

  static final String CHAIN_KIND = "operator";
  static final String EVENT_PROFILE_LIST_SEARCHED = "profile_list_searched";

  private final ProfileListRepository profileListRepository;
  private final AuditEventWriter auditEventWriter;

  public OperatorProfileListService(
      ProfileListRepository profileListRepository, AuditEventWriter auditEventWriter) {
    this.profileListRepository = profileListRepository;
    this.auditEventWriter = auditEventWriter;
  }

  public ProfileListResult search(
      OperatorIdentity identity,
      ProfileListFilter filter,
      ProfileListSortField sortField,
      SortOrder sortOrder,
      ProfileListPage page) {
    profileListRepository.ensureOperatorChain(identity.operatorId());
    ProfileListResult result = profileListRepository.search(filter, sortField, sortOrder, page);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", filter.status());
    payload.put("provenance", filter.provenance());
    payload.put("branchCode", filter.branchCode());
    payload.put("rejectionReasonCode", filter.rejectionReasonCode());
    payload.put(
        "submittedFrom",
        filter.submittedFrom() == null
            ? null
            : DateTimeFormatter.ISO_INSTANT.format(filter.submittedFrom()));
    payload.put(
        "submittedTo",
        filter.submittedTo() == null
            ? null
            : DateTimeFormatter.ISO_INSTANT.format(filter.submittedTo()));
    payload.put("searchText", filter.searchText());
    payload.put("sortField", sortField.name());
    payload.put("sortOrder", sortOrder.name());
    payload.put("page", page.page());
    payload.put("pageSize", page.pageSize());
    payload.put("total", result.total());

    auditEventWriter.append(
        new AuditEvent(
            CHAIN_KIND,
            identity.operatorId(),
            EVENT_PROFILE_LIST_SEARCHED,
            "operator",
            identity.operatorId(),
            null,
            null,
            UUID.randomUUID(),
            CanonicalJson.object(OperatorAuditPayload.withActorRole(identity, payload))));

    return result;
  }
}
