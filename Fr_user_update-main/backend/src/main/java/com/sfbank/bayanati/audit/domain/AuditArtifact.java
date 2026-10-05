package com.sfbank.bayanati.audit.domain;

/**
 * One row destined for {@code audit.audit_artifact} — a raw external-system exchange stored
 * byte-identical, alongside (never inside) the hash-chained {@code audit_event} row that references
 * it. Exists precisely so a lawful PII purge can null {@code body} later while the event's own
 * {@code payload_json} — permanently hash-chained, never erasable — carries only {@code sha256} and
 * metadata, never the artifact bytes themselves (docs/components/persistence.md: "Hash covers
 * audit_artifact.sha256, never the body, so a lawful PII purge of the body leaves the chain
 * verifiable").
 *
 * @param kind one of {@code audit.audit_artifact}'s CHECK values, e.g. {@code uqudo_scan_jws},
 *     {@code civil_registry_response}
 * @param mediaType e.g. {@code application/jose}, {@code application/json}
 * @param body the raw bytes, byte-identical, never re-encoded
 */
public record AuditArtifact(String kind, String mediaType, byte[] body) {}
