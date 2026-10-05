package com.sfbank.bayanati.auth.config;

import com.sfbank.bayanati.auth.domain.DuplicateUsernameException;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * AD-002e's real answer to "admin-created accounts, no self-registration" (CLAUDE.md task §2): the
 * task's OUT-OF-SCOPE line excludes back-office UI/admin screens, and nothing in "Build it" asks
 * for an HTTP account-CRUD surface, but shipping NO mechanism at all would leave "admin-created" as
 * a decision with no way to actually do it — SQL-only creation would quietly make it "DBA-created"
 * instead. This one profile-gated {@link ApplicationRunner}, mirroring {@code
 * reference.config.ReferenceDocumentPublicationRunner}'s exact precedent, both bootstraps the first
 * admin and creates every later operator/viewer account. The still-missing piece is an HTTP surface
 * for listing/disabling/role-changing an existing account — filed to BACKLOG.md, not built
 * speculatively.
 *
 * <p>Invoked via {@code java -jar target/backend-0.0.1-SNAPSHOT.jar
 * --spring.profiles.active=create-operator-account --spring.main.web-application-type=none
 * --username=... --display-name=... --role=viewer|operator|admin}. No dedicated {@code
 * application-*.properties} override is needed (unlike the reference-publisher precedent, which
 * needed a role switch to {@code fru_migrator}) — {@code fru_app} already holds {@code INSERT} on
 * {@code app.operator_user} (V0057). Full instructions: {@code
 * db/post-migrate/03-create-operator-account.md}.
 *
 * <p>{@code ApplicationArguments}, not raw {@code CommandLineRunner} args, because {@code
 * --key=value} option parsing is exactly what this needs and Spring already provides it.
 */
@Component
@Profile("create-operator-account")
class CreateOperatorAccountRunner implements ApplicationRunner {

  private static final int PASSWORD_ENTROPY_BYTES = 24;

  private final OperatorUserRepository operatorUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final IntConsumer exiter;

  /**
   * BL-024 (found live at S6-01): with two 3-argument constructors and neither annotated, Spring's
   * constructor resolution has no unambiguous candidate and no no-arg fallback, so it fails at
   * context refresh with {@code NoSuchMethodException: CreateOperatorAccountRunner.<init>()} — the
   * runner could never actually be invoked through {@code java -jar ...
   * --spring.profiles.active=create-operator-account}, the only documented way to provision an
   * operator account. {@code @Autowired} here (and nowhere else — the {@link IntConsumer}
   * constructor below stays a deliberate test-only seam) is the entire fix.
   */
  @Autowired
  CreateOperatorAccountRunner(
      OperatorUserRepository operatorUserRepository,
      PasswordEncoder passwordEncoder,
      ConfigurableApplicationContext context) {
    // Real process exit, production wiring: SpringApplication.exit(...) runs ExitCodeGenerators
    // and closes the context before the JVM actually terminates.
    this(
        operatorUserRepository,
        passwordEncoder,
        code -> System.exit(SpringApplication.exit(context, () -> code)));
  }

  /** Test-only seam: a fake {@link IntConsumer} observes the exit code without killing the JVM. */
  CreateOperatorAccountRunner(
      OperatorUserRepository operatorUserRepository,
      PasswordEncoder passwordEncoder,
      IntConsumer exiter) {
    this.operatorUserRepository = operatorUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.exiter = exiter;
  }

  @Override
  public void run(ApplicationArguments args) {
    String username;
    String displayName;
    OperatorRole role;
    try {
      username = requireOption(args, "username");
      displayName = requireOption(args, "display-name");
      role = OperatorRole.valueOf(requireOption(args, "role").toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      System.err.println(invalid.getMessage());
      exit(1);
      return;
    }

    byte[] randomBytes = new byte[PASSWORD_ENTROPY_BYTES];
    new SecureRandom().nextBytes(randomBytes);
    String oneTimePassword = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    String hash = passwordEncoder.encode(oneTimePassword);

    UUID userId;
    try {
      // createdBy is null: this is an out-of-band CLI act with no admin session behind it (see
      // V0057's column comment).
      userId = operatorUserRepository.create(username, displayName, role, hash, null);
    } catch (DuplicateUsernameException duplicate) {
      System.err.println(duplicate.getMessage());
      exit(1);
      return;
    }

    // Printed to stdout exactly ONCE. Never a log statement (a logger's output can be captured or
    // forwarded elsewhere), never written to any audit payload, file, or session report --
    // CLAUDE.md's no-secrets rule, no exception for a generated one-time value.
    System.out.println("Created " + role.name().toLowerCase(Locale.ROOT) + " account " + userId);
    System.out.println("username: " + username);
    System.out.println("one-time password (must be changed on first sign-in): " + oneTimePassword);
    exit(0);
  }

  private static String requireOption(ApplicationArguments args, String name) {
    List<String> values = args.getOptionValues(name);
    if (values == null || values.isEmpty() || values.get(0).isBlank()) {
      throw new IllegalArgumentException("--" + name + "=... is required");
    }
    return values.get(0);
  }

  private void exit(int code) {
    exiter.accept(code);
  }
}
