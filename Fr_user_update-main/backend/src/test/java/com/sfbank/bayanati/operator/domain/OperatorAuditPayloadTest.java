package com.sfbank.bayanati.operator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Spring context, no database, no clock — this is a {@code domain} package and
 * CLAUDE.md's package rule scopes it to exactly that.
 */
class OperatorAuditPayloadTest {

  private static OperatorIdentity identity(String role) {
    return new OperatorIdentity(
        "11111111-1111-1111-1111-111111111111", OperatorAccessLevel.OPERATOR, role);
  }

  @Test
  void stampsTheActorRoleOntoAnEmptyPayload() {
    Map<String, Object> stamped = OperatorAuditPayload.withActorRole(identity("admin"), Map.of());

    assertEquals(Map.of("actorRole", "admin"), stamped);
  }

  @Test
  void keepsEveryFieldTheCallerSupplied() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reasonCode", "R01");
    payload.put("internalNote", null);

    Map<String, Object> stamped = OperatorAuditPayload.withActorRole(identity("operator"), payload);

    assertEquals("R01", stamped.get("reasonCode"));
    assertTrue(stamped.containsKey("internalNote"));
    assertEquals("operator", stamped.get("actorRole"));
    assertEquals(3, stamped.size());
  }

  @Test
  void distinguishesAnAdminFromAnOperatorTheWholePointOfTheField() {
    // R-054: after AD-013 one person can edit a profile's editable fields and approve it, and the
    // audit trail is the SOLE compensating control. (Until AD-022 that chain also included manually
    // completing the profile; removing that link narrows R-054 but does not close it.) Both actors
    // write actor_kind='operator' and an
    // actor_id UUID, so without this field an admin's approve and an operator's approve are
    // byte-identical in the chain, answerable only by joining back to app.operator_user.role --
    // which is mutable, and so cannot testify about what was true at the time.
    assertEquals(
        "admin", OperatorAuditPayload.withActorRole(identity("admin"), Map.of()).get("actorRole"));
    assertEquals(
        "operator",
        OperatorAuditPayload.withActorRole(identity("operator"), Map.of()).get("actorRole"));
    assertEquals(
        "viewer",
        OperatorAuditPayload.withActorRole(identity("viewer"), Map.of()).get("actorRole"));
  }

  @Test
  void doesNotMutateTheCallersMap() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("format", "csv");

    OperatorAuditPayload.withActorRole(identity("admin"), payload);

    assertEquals(Map.of("format", "csv"), payload);
  }

  @Test
  void refusesToOverwriteAnActorRoleTheCallerAlreadySet() {
    // A caller that sets its own actorRole is confused about who owns this field. Failing loudly
    // beats silently preferring one of the two values in an append-only, unamendable record.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("actorRole", "operator");

    assertThrows(
        IllegalArgumentException.class,
        () -> OperatorAuditPayload.withActorRole(identity("admin"), payload));
  }
}
