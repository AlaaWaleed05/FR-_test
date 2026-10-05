package com.sfbank.bayanati.operator.web;

/**
 * What one edit stored.
 *
 * <p>Deliberately NOT a whole refreshed {@code ProfileDetailResponse}. The client reloads the
 * profile after an edit, which re-derives {@code editableFields} server-side — and it must, because
 * an edit can change what else is editable (clearing the last «أخرى» income source, for instance,
 * is impossible here, but the principle holds and the derivation belongs on the read path). Sending
 * a whole detail from a write endpoint would also mint a second copy of the densest PII object in
 * the system on every keystroke-sized change.
 *
 * @param fieldKey the {@code EditableField} name, echoed so a client can match the response to the
 *     row it sent
 * @param value the value as STORED, after trimming — not as submitted. A client that renders the
 *     submitted string rather than this one would show an untrimmed value the database does not
 *     hold.
 */
public record FieldEditResponse(String fieldKey, String value) {}
