package com.sfbank.bayanati.reference.web;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/reference/manifest}'s JSON body (AD-002f §5.1).
 *
 * @param verifiableChannels R-042's reserved field — "a small additive change to ... AD-002f's
 *     manifest shape — which channels this deployment can actually verify" (RISKS.md). Statically
 *     {@code ["sms","whatsapp","email"]} here: R-042's actual journey decision is NOT made by this
 *     task (OUT OF SCOPE), this only reserves the wire position per the research report §5.1.
 *     <p><strong>Not covered by {@code catalogHash}</strong> — {@link
 *     com.sfbank.bayanati.reference.domain.ManifestHasher} hashes only the list entries. Safe while
 *     this field is a hardcoded constant, as it is here; whoever wires R-042's real deployment
 *     configuration into it must also feed it into the hash, or a client holding a cached manifest
 *     will keep 304-revalidating against an unchanged {@code catalogHash} and never see the new
 *     channel set.
 */
public record ManifestResponse(
    String catalogHash,
    Instant generatedAt,
    List<ManifestListEntryResponse> lists,
    List<String> verifiableChannels) {}
