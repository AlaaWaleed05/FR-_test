package com.sfbank.bayanati.operator.domain;

/**
 * One artifact's bytes, ready to serve to an operator — already proved to belong to the profile in
 * the path, already checksum-verified by {@code app.artifact_read()} on the way out.
 *
 * @param kind the {@code app.artifact_ref.kind} that was read; carried so the audit payload can
 *     record it without a second query
 * @param contentType what the ROW DECLARED, not what will be served — {@link OperatorImagePolicy}
 *     pins the served value from its own allow-list, because this column can lie
 * @param bytes the artifact itself; never empty, since a NULL body is an absence the repository
 *     resolves to {@code Optional.empty()} rather than a zero-length array
 */
public record OperatorImage(String kind, String contentType, byte[] bytes) {}
