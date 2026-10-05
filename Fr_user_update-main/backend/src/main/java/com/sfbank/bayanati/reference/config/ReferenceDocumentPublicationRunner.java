package com.sfbank.bayanati.reference.config;

import com.sfbank.bayanati.reference.jdbc.ReferenceDocumentPublisher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * AD-002f's publication step (research report §6.7) — "a documented step, not an admin endpoint,
 * which would drag in AD-002e." This is that documented step: a one-shot command, never an HTTP
 * endpoint, so nothing about it is reachable over the network and it needs no authentication
 * decision.
 *
 * <p>Gated behind the {@code publish-reference-documents} Spring profile — inert in every other
 * profile, including the deployed application's normal runtime. Invoked via {@code java -jar
 * target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=publish-reference-documents}, full
 * instructions at {@code db/post-migrate/02-publish-reference-documents.md} (same {@code
 * db/post-migrate/} precedent as Layer 3's event-trigger script, implemented in Java instead of SQL
 * because deterministic document generation is a Java concern — see {@code
 * ReferenceDocumentGenerator}'s Javadoc). {@code application-publish-reference-documents
 * .properties} overrides the datasource to connect as {@code fru_migrator} — {@code
 * ReferenceDocumentPublisher} writes {@code ref.reference_list_document} and {@code
 * ref.reference_list_version.content_hash}, neither of which {@code fru_app} (the normal runtime
 * role) holds a grant on.
 *
 * <p>Exits the process after running (never starts serving — {@code
 * spring.main.web-application-type=none} in the same properties file means no port is even bound)
 * rather than lingering as a resident process.
 */
@Component
@Profile("publish-reference-documents")
class ReferenceDocumentPublicationRunner implements CommandLineRunner {

  private static final Logger log =
      LoggerFactory.getLogger(ReferenceDocumentPublicationRunner.class);

  private final ReferenceDocumentPublisher publisher;
  private final ConfigurableApplicationContext context;

  ReferenceDocumentPublicationRunner(
      ReferenceDocumentPublisher publisher, ConfigurableApplicationContext context) {
    this.publisher = publisher;
    this.context = context;
  }

  @Override
  public void run(String... args) {
    List<String> published = publisher.publishAllCurrentVersions();
    log.info("Published reference documents for: {}", published);
    System.exit(SpringApplication.exit(context, () -> 0));
  }
}
