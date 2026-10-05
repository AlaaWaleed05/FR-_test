package com.sfbank.bayanati.auth.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BL-024's real regression test. {@link CreateOperatorAccountRunnerTest} deliberately never goes
 * through Spring (its own Javadoc says so) — it calls the test-only {@code IntConsumer} constructor
 * directly, which is exactly why it never caught the ambiguous-constructor wiring fault found live
 * at S6-01: two 3-argument constructors, neither {@code @Autowired}, no no-arg fallback, so
 * Spring's real constructor resolution had no unambiguous candidate and failed at context refresh
 * with {@code NoSuchMethodException}.
 *
 * <p>This test uses a real {@link AnnotationConfigApplicationContext} — the actual {@code
 * AutowiredAnnotationBeanPostProcessor}/{@code ConstructorResolver} machinery a full Spring Boot
 * context also uses, not a mock standing in for it — so it fails exactly the way the real
 * deployment did before the fix. It deliberately does NOT boot the full application (no database,
 * no other beans): {@code ApplicationRunner.run()} is invoked by {@code SpringApplication} itself,
 * never by a plain {@code ApplicationContext.refresh()}, so building only this bean proves
 * constructor resolution without ever calling {@link CreateOperatorAccountRunner#run}, which would
 * call {@code System.exit} via the production constructor's real exiter — fatal to a test JVM.
 * {@code ConfigurableApplicationContext} resolves to the context itself for free: {@code
 * AbstractApplicationContext#prepareBeanFactory} registers {@code ApplicationContext.class} as a
 * resolvable dependency, and {@code ConfigurableApplicationContext} is a subtype, so no extra bean
 * registration is needed for it — the same reason the production wiring resolves it in a real
 * Spring Boot context.
 *
 * <p>Revert-proof (CLAUDE.md): with {@code @Autowired} removed from the production constructor,
 * this test's {@code refresh()} call throws {@code BeanCreationException}/{@code
 * NoSuchMethodException}, reproducing BL-024 exactly — confirmed by hand before restoring the fix,
 * not asserted from reading the code alone.
 */
class CreateOperatorAccountRunnerSpringWiringTest {

  @Test
  void springResolvesTheProductionConstructorUnambiguouslyWhenTheProfileIsActive() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("create-operator-account");
      context.registerBean(OperatorUserRepository.class, () -> mock(OperatorUserRepository.class));
      context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
      context.register(CreateOperatorAccountRunner.class);

      context.refresh();

      assertNotNull(context.getBean(CreateOperatorAccountRunner.class));
    }
  }

  @Test
  void theBeanIsAbsentWhenTheProfileIsNotActive() {
    // Not the bug this class exists to catch, but worth pinning down alongside it: @Profile is
    // still doing its job after the fix, so this runner never accidentally activates outside the
    // one profile it is meant for.
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(OperatorUserRepository.class, () -> mock(OperatorUserRepository.class));
      context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
      context.register(CreateOperatorAccountRunner.class);

      context.refresh();

      assertNotNull(context);
      org.junit.jupiter.api.Assertions.assertTrue(
          context.getBeanNamesForType(CreateOperatorAccountRunner.class).length == 0);
    }
  }
}
