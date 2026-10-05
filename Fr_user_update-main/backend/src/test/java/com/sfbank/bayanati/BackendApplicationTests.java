package com.sfbank.bayanati;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

// DataSource/Flyway autoconfiguration excluded so this context-loads smoke test stays
// independent of a running Postgres — see the "known gap" note in the S2-01 session
// report: this means the standard gates no longer prove the app can reach a database.
// S2-02 adds an integration test that does.
//
// S2-02 correction: these were the pre-4.0 fully-qualified names. Spring Boot 4.1 split
// autoconfiguration into per-module jars/packages (org.springframework.boot.jdbc.autoconfigure,
// org.springframework.boot.flyway.autoconfigure, ...). The old names above did not resolve to
// any class on this classpath, yet this test still passed — until adding the (also S2-02) new
// org.springframework.boot:spring-boot-flyway dependency, at which point it broke: with that
// module absent, nothing in FlywayAutoConfiguration's dependency chain forced the primary
// spring.datasource DataSourceAutoConfiguration to actually create a HikariDataSource; once
// present, it did, hit the unresolved ${FRU_APP_PASSWORD}/${DB_MIGRATOR_PASSWORD} placeholders
// with no real values, and failed context startup. The stale (non-resolving) exclusion names
// were never actually excluding anything correctly. Fixed to the real current names below.
//
// S3-01, two additions, both forced by the first service code existing:
//  1. fru.core-banking.client, because CoreBankingClientConfiguration deliberately has no default
//     — an unset property fails startup rather than letting a backend answer a real account check
//     from a stub. Every context, including this smoke test, states its choice.
//  2. A mock JdbcTemplate. JdbcAuditEventWriter needs one, and with the DataSource
//     autoconfiguration excluded nothing creates it. Mocking it keeps this test's whole point
//     intact — a wiring smoke test that must not need Docker — while still proving the new
//     beans wire together. What the writer actually does against a real database is proven by
//     AccountCheckIntegrationTest, which is where that belongs.
//
// S3-05: fru.messaging.{sms,whatsapp,email}.provider, same reasoning as fru.core-banking.client
// — MessageSenderConfiguration also has no default. JdbcNotificationOutboxRepository needs the
// same mocked JdbcTemplate above. OutboxDispatcher additionally needs a PlatformTransactionManager
// (it wraps recordResult+audit in one transaction, per AD-005 §6) -- with
// DataSourceAutoConfiguration
// excluded nothing creates one, so it is mocked here for the same reason JdbcTemplate is.
//
// S3-12: fru.uqudo.client and fru.civil-registry.client, same no-default reasoning —
// UqudoClientConfiguration and CivilRegistryClientConfiguration each fail startup on an unset
// property rather than silently running a real identity scan against a stub.
//
// S4-01: a mock NamedParameterJdbcTemplate. JdbcProfileListRepository is the first repository in
// this codebase to need one (its query is dynamic enough — optional filters, one search term
// referenced many times, a whitelisted sort column — that named parameters materially simplify
// it) — with DataSourceAutoConfiguration excluded nothing creates it, same reasoning as the mocked
// JdbcTemplate above.
//
// S6-04: @EnableScheduling is now on BackendApplication, so this context starts a real scheduler
// thread with OutboxDispatchScheduler registered on it. Its poll is pushed an hour out so it never
// fires here — this context's JdbcTemplate is a mock, so a tick would exercise nothing real, and a
// wiring smoke test should not depend on a timer's behaviour either way.
// AbstractPostgresIntegrationTest
// registers the same two properties, for a much sharper reason — see its Javadoc.
@SpringBootTest(
    properties = {
      "fru.core-banking.client=stub",
      "fru.notification.outbox.poll-initial-delay=1h",
      "fru.notification.outbox.poll-interval=1h",
      "fru.messaging.sms.provider=stub",
      "fru.messaging.whatsapp.provider=stub",
      "fru.messaging.email.provider=stub",
      "fru.uqudo.client=stub",
      "fru.civil-registry.client=stub",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
class BackendApplicationTests {

  @TestConfiguration
  static class NoDatabaseConfiguration {
    @Bean
    JdbcTemplate jdbcTemplate() {
      return mock(JdbcTemplate.class);
    }

    @Bean
    PlatformTransactionManager platformTransactionManager() {
      return mock(PlatformTransactionManager.class);
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
      return mock(NamedParameterJdbcTemplate.class);
    }
  }

  @Test
  void contextLoads() {}
}
