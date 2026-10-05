package com.sfbank.bayanati.operator.web;

/**
 * The body of a per-field edit. One field, one value — the field itself is the path variable, so a
 * request can never carry a field key that disagrees with the URL it was sent to.
 *
 * @param value the operator's input, trimmed and bounded by {@code FieldEditValidator}. Never
 *     null-able in practice: a blank is refused with a 400, because every editable field is
 *     mandatory in the mobile journey and an empty edit would delete data the journey required.
 */
public record FieldEditRequest(String value) {}
