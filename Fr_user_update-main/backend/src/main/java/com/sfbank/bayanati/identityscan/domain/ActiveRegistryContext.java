package com.sfbank.bayanati.identityscan.domain;

/**
 * The profile's current active {@code identity_cycle} and its {@code registry_result}, read
 * together for every Stage 9 action (accept, wrong-number, wrong-details, retry).
 *
 * @param uqudoDocumentType Uqudo's own vocabulary ({@code "SDN_ID"}/{@code "PASSPORT"}), straight
 *     from {@code app.scan_result.document_type} — translated back to the app vocabulary via {@link
 *     DocumentTypes#fromUqudoDocumentType} only where the retry-budget columns need it
 * @param registryState {@code app.registry_result.state} — {@code pending}/{@code ok}/{@code
 *     not_found}/{@code unreachable}
 */
public record ActiveRegistryContext(
    java.util.UUID cycleId,
    String identityNumber,
    String uqudoDocumentType,
    String registryState,
    int attempts) {}
