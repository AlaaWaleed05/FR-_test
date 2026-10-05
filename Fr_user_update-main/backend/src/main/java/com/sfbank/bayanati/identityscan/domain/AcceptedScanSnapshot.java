package com.sfbank.bayanati.identityscan.domain;

import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import java.util.UUID;

/**
 * Everything needed to answer a customer's own re-upload of an already-accepted enrolment JWS
 * without repeating any external call — BL-034. Read from the profile's <strong>active</strong>
 * cycle only, so a snapshot can never describe another profile's scan.
 *
 * <p>Distinct from {@link ActiveRegistryContext}, which the Stage 9 actions use: that one answers
 * "what may this profile do next" and deliberately carries no stored registry <em>fields</em>. This
 * one answers "what did the first attempt already return", which is the whole display payload.
 *
 * @param uqudoJti {@code app.scan_result.uqudo_jti} — compared against the incoming JWS's {@code
 *     jti} to tell the customer's own retry from a replay. The comparison is only ever made after
 *     the JWS has been signature-verified, so this is not a trusted-input path.
 * @param uqudoDocumentType Uqudo's own vocabulary ({@code "SDN_ID"}/{@code "PASSPORT"}), as stored
 *     — translated back through {@link DocumentTypes#fromUqudoDocumentType} by the caller, exactly
 *     as {@code ActiveRegistryContext} is
 * @param registryState {@code app.registry_result.state}. BL-034 short-circuits on {@code ok} only;
 *     every other value falls through to the unchanged submission path (BL-035)
 * @param registryFields the stored Civil Registry values, rebuilt from {@code app.registry_result}
 *     (V0023, V0062) so returning the existing payload never re-runs the lookup. {@code null} when
 *     the lookup has not succeeded. Its {@code photograph} is always {@code null}: the bytes live
 *     in {@code app.artifact_ref} and Stage 9 fetches them through the image endpoint, so carrying
 *     them here would move a portrait through a payload that never needs it.
 */
public record AcceptedScanSnapshot(
    UUID cycleId,
    String uqudoJti,
    String identityNumber,
    String uqudoDocumentType,
    String registryState,
    RegistryLookupResult registryFields) {}
