package com.sfbank.bayanati.audit.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the RFC 8785 (JSON Canonicalization Scheme) form of a flat JSON object, which is what
 * {@code audit.audit_event.payload_json} is documented to hold (V0002).
 *
 * <p>Canonical form matters because that column is the text the hash chain covers. Two runs that
 * record the same facts must produce byte-identical text, or a later {@code
 * audit.verify_chain()}/{@code audit.seal_verify()} comparison is meaningless. A general-purpose
 * JSON serializer gives no such guarantee — key order follows map iteration order.
 *
 * <p><strong>Deliberately a subset of RFC 8785:</strong> flat objects only, with {@code String},
 * integral, {@code Boolean} and {@code null} values. Nested objects, arrays and floating-point
 * numbers are rejected rather than serialized approximately — RFC 8785's number rule is ECMAScript
 * {@code Number::toString}, which is not worth implementing before an audit payload needs it. Widen
 * it when one does, with tests, rather than reaching for a library whose ordering is incidental.
 *
 * <p><strong>Three inputs are rejected outright rather than producing output that is subtly
 * wrong.</strong> Each would leave a permanent, unfixable record, because what this produces is
 * hashed into an append-only chain:
 *
 * <ul>
 *   <li><strong>U+0000.</strong> Escaping it is correct JSON and correct RFC 8785, but {@code
 *       audit.audit_event.payload} is a generated {@code payload_json::jsonb} column and PostgreSQL
 *       refuses {@code \u0000} in a text-to-jsonb cast (SQLSTATE 22P05). The insert would fail with
 *       nothing recorded, so the value can never be audited and must be refused earlier.
 *   <li><strong>An unpaired surrogate.</strong> RFC 8785 requires well-formed output. Appended raw
 *       it becomes {@code ?} when the driver encodes to UTF-8 — the chain stays self-consistent
 *       while the record silently differs from what was received, which is the worst failure an
 *       audit trail can have.
 *   <li><strong>An integral value outside ±2^53.</strong> RFC 8785 serialises numbers by
 *       ECMAScript's {@code Number::toString}, which would round such a value; emitting the exact
 *       decimal instead diverges from every other JCS implementation. That only matters if
 *       something outside this codebase ever recomputes the hash — which is precisely what a
 *       regulator or an investigator would do.
 * </ul>
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  /**
   * Serializes {@code members} as a canonical JSON object: keys sorted by UTF-16 code unit, no
   * insignificant whitespace, minimal escaping.
   *
   * @throws IllegalArgumentException if a key is null, or a value is of an unsupported type
   */
  public static String object(Map<String, ?> members) {
    List<String> keys = new ArrayList<>(members.keySet());
    if (keys.contains(null)) {
      throw new IllegalArgumentException("a canonical JSON object cannot have a null key");
    }
    // String's natural ordering IS UTF-16 code-unit ordering (String.compareTo compares char by
    // char), which is exactly what RFC 8785 section 3.2.3 specifies for member sorting.
    keys.sort(Comparator.naturalOrder());

    StringBuilder out = new StringBuilder("{");
    boolean first = true;
    for (String key : keys) {
      if (!first) {
        out.append(',');
      }
      first = false;
      appendString(out, key);
      out.append(':');
      appendValue(out, key, members.get(key));
    }
    return out.append('}').toString();
  }

  private static void appendValue(StringBuilder out, String key, Object value) {
    switch (value) {
      case null -> out.append("null");
      case String s -> appendString(out, s);
      case Boolean b -> out.append(b.booleanValue());
      case Integer i -> out.append(i.intValue());
      case Long l -> out.append(safeInteger(key, l.longValue()));
      case Short s -> out.append(s.intValue());
      case Byte b -> out.append(b.intValue());
      default ->
          throw new IllegalArgumentException(
              "unsupported canonical JSON value type for key '"
                  + key
                  + "': "
                  + value.getClass().getName()
                  + " (supported: String, Boolean, Byte, Short, Integer, Long, null)");
    }
  }

  /** RFC 8785 section 3.2.2.2: the two mandatory escapes, the shorthands, and \\u00xx otherwise. */
  private static void appendString(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c == 0) {
            throw new IllegalArgumentException(
                "a canonical JSON string cannot contain U+0000: PostgreSQL rejects it in the"
                    + " payload_json::jsonb cast, so the value could never be audited");
          }
          if (Character.isSurrogate(c) && !isWellFormedPair(value, i)) {
            throw new IllegalArgumentException(
                "a canonical JSON string cannot contain an unpaired surrogate at index "
                    + i
                    + ": RFC 8785 output must be well-formed, and encoding it would silently"
                    + " replace it, making the audit record differ from what was received");
          }
          if (c < 0x20) {
            // String.format is locale-independent for %04x (verified across ar/fa/hi/tr numbering
            // systems) -- do not "fix" this into a locale-aware formatter.
            out.append(String.format("\\u%04x", (int) c));
          } else {
            // Everything else, Arabic included, is emitted as-is: RFC 8785 output is UTF-8 and
            // does NOT escape non-ASCII. Escaping it would still be valid JSON but a different
            // byte sequence, and therefore a different hash.
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** True when the surrogate at {@code i} is half of a correctly ordered surrogate pair. */
  private static boolean isWellFormedPair(String value, int i) {
    char c = value.charAt(i);
    if (Character.isHighSurrogate(c)) {
      return i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1));
    }
    return i > 0 && Character.isHighSurrogate(value.charAt(i - 1));
  }

  /** RFC 8785 numbers are ECMAScript doubles; outside this range the two disagree. */
  private static long safeInteger(String key, long value) {
    long limit = 1L << 53;
    if (value > limit || value < -limit) {
      throw new IllegalArgumentException(
          "canonical JSON value for key '"
              + key
              + "' is outside the range RFC 8785 can represent exactly (±2^53): "
              + value);
    }
    return value;
  }
}
