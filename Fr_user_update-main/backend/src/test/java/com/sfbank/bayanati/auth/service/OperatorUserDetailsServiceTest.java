package com.sfbank.bayanati.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class OperatorUserDetailsServiceTest {

  private final OperatorUserRepository operatorUserRepository = mock(OperatorUserRepository.class);
  private final OperatorUserDetailsService service =
      new OperatorUserDetailsService(operatorUserRepository);

  @Test
  void loadsAKnownAccountUnfilteredByIsEnabled() {
    UUID userId = UUID.randomUUID();
    when(operatorUserRepository.findByUsername("s405.uds"))
        .thenReturn(
            Optional.of(
                new OperatorAccount(
                    userId, "s405.uds", "UDS", OperatorRole.VIEWER, "{bcrypt}h", false, false)));

    UserDetails details = service.loadUserByUsername("s405.uds");

    assertEquals("s405.uds", details.getUsername());
    assertEquals(false, details.isEnabled(), "a disabled row must still load, not 404");
  }

  @Test
  void unknownUsernameThrows() {
    when(operatorUserRepository.findByUsername("s405.nouds")).thenReturn(Optional.empty());

    assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("s405.nouds"));
  }
}
