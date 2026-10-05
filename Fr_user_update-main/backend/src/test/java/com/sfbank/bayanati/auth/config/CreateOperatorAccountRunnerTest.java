package com.sfbank.bayanati.auth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.auth.domain.DuplicateUsernameException;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * No Spring context, no database: {@link CreateOperatorAccountRunner}'s test-only constructor (an
 * {@code IntConsumer} exiter) is what makes this possible without actually terminating the test JVM
 * via {@code System.exit}.
 */
class CreateOperatorAccountRunnerTest {

  private final OperatorUserRepository operatorUserRepository = mock(OperatorUserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final java.util.List<Integer> exitCodes = new java.util.ArrayList<>();
  private final CreateOperatorAccountRunner runner =
      new CreateOperatorAccountRunner(operatorUserRepository, passwordEncoder, exitCodes::add);

  private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;

  @BeforeEach
  void captureStdout() {
    System.setOut(new PrintStream(capturedOut));
  }

  @AfterEach
  void restoreStdout() {
    System.setOut(originalOut);
  }

  @Test
  void createsTheAccountAndPrintsTheOneTimePasswordExactlyOnce() {
    UUID userId = UUID.randomUUID();
    when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
    when(operatorUserRepository.create(
            eq("s405.cli1"), eq("CLI One"), eq(OperatorRole.VIEWER), eq("{bcrypt}hash"), isNull()))
        .thenReturn(userId);

    runner.run(
        new DefaultApplicationArguments(
            "--username=s405.cli1", "--display-name=CLI One", "--role=viewer"));

    assertEquals(java.util.List.of(0), exitCodes);
    String output = capturedOut.toString();
    assertTrue(output.contains(userId.toString()));
    assertTrue(output.contains("s405.cli1"));
    assertTrue(
        output.toLowerCase(java.util.Locale.ROOT).contains("one-time password"),
        "the generated password must actually be printed, once, to stdout");
  }

  @Test
  void missingUsernameExitsNonZeroWithoutCreatingAnAccount() {
    runner.run(new DefaultApplicationArguments("--display-name=CLI One", "--role=viewer"));

    assertEquals(java.util.List.of(1), exitCodes);
    verify(operatorUserRepository, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void invalidRoleExitsNonZeroWithoutCreatingAnAccount() {
    runner.run(
        new DefaultApplicationArguments(
            "--username=s405.cli2", "--display-name=CLI Two", "--role=superadmin"));

    assertEquals(java.util.List.of(1), exitCodes);
    verify(operatorUserRepository, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void duplicateUsernameExitsNonZero() {
    when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
    when(operatorUserRepository.create(any(), any(), any(), any(), isNull()))
        .thenThrow(new DuplicateUsernameException("username 's405.cli3' is already taken"));

    runner.run(
        new DefaultApplicationArguments(
            "--username=s405.cli3", "--display-name=CLI Three", "--role=operator"));

    assertEquals(java.util.List.of(1), exitCodes);
  }

  @Test
  void createdByIsAlwaysNullForACliCreatedAccount() {
    when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
    when(operatorUserRepository.create(any(), any(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());

    runner.run(
        new DefaultApplicationArguments(
            "--username=s405.cli4", "--display-name=CLI Four", "--role=admin"));

    verify(operatorUserRepository)
        .create(eq("s405.cli4"), eq("CLI Four"), eq(OperatorRole.ADMIN), any(), isNull());
  }
}
