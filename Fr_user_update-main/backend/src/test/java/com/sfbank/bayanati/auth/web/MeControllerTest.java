package com.sfbank.bayanati.auth.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeControllerTest {

  private final MeController controller = new MeController();

  @Test
  void returnsUsernameDisplayNameRoleAndMustChangePassword() {
    OperatorAccount account =
        new OperatorAccount(
            UUID.randomUUID(),
            "s405.me",
            "مطّلع الفرع",
            OperatorRole.OPERATOR,
            "{bcrypt}h",
            false,
            true);

    MeResponse response = controller.me(new OperatorUserDetails(account));

    assertEquals("s405.me", response.username());
    assertEquals("مطّلع الفرع", response.displayName());
    assertEquals("operator", response.role());
    assertFalse(response.mustChangePassword());
  }
}
