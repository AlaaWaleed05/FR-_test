package com.sfbank.bayanati.auth.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.auth.domain.IncorrectCurrentPasswordException;
import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.PasswordTooLongException;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import com.sfbank.bayanati.auth.service.PasswordChangeService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.server.ResponseStatusException;

class PasswordChangeControllerTest {

  private final PasswordChangeService passwordChangeService = mock(PasswordChangeService.class);
  private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
  private final PasswordChangeController controller =
      new PasswordChangeController(passwordChangeService, userDetailsService);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void rebuildsAndSavesTheSecurityContextAfterASuccessfulChange() {
    UUID userId = UUID.randomUUID();
    OperatorAccount before =
        new OperatorAccount(
            userId, "s405.pwchange", "PW Change", OperatorRole.VIEWER, "{bcrypt}old", true, true);
    OperatorAccount after =
        new OperatorAccount(
            userId, "s405.pwchange", "PW Change", OperatorRole.VIEWER, "{bcrypt}new", false, true);
    when(userDetailsService.loadUserByUsername("s405.pwchange"))
        .thenReturn(new OperatorUserDetails(after));

    ResponseEntity<Void> response =
        controller.changePassword(
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            new OperatorUserDetails(before),
            new PasswordChangeRequest("old-pw", "new-pw"));

    assertEquals(204, response.getStatusCode().value());
    verify(passwordChangeService).changePassword(userId, "{bcrypt}old", "old-pw", "new-pw");
    // The SAME session's SecurityContext now carries the FULL, non-restricted authorities.
    OperatorUserDetails rebuilt =
        (OperatorUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    assertFalse(rebuilt.mustChangePassword());
  }

  @Test
  void incorrectCurrentPasswordIs401() {
    UUID userId = UUID.randomUUID();
    OperatorAccount before =
        new OperatorAccount(
            userId, "s405.pwwrong", "PW Wrong", OperatorRole.VIEWER, "{bcrypt}old", true, true);
    org.mockito.Mockito.doThrow(new IncorrectCurrentPasswordException("nope"))
        .when(passwordChangeService)
        .changePassword(userId, "{bcrypt}old", "wrong", "new-pw");

    ResponseStatusException thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.changePassword(
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    new OperatorUserDetails(before),
                    new PasswordChangeRequest("wrong", "new-pw")));
    assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
  }

  @Test
  void tooLongNewPasswordIs400() {
    UUID userId = UUID.randomUUID();
    OperatorAccount before =
        new OperatorAccount(
            userId, "s405.pwlong", "PW Long", OperatorRole.VIEWER, "{bcrypt}old", true, true);
    org.mockito.Mockito.doThrow(new PasswordTooLongException("too long"))
        .when(passwordChangeService)
        .changePassword(userId, "{bcrypt}old", "old-pw", "too-long");

    ResponseStatusException thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.changePassword(
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    new OperatorUserDetails(before),
                    new PasswordChangeRequest("old-pw", "too-long")));
    assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
  }
}
