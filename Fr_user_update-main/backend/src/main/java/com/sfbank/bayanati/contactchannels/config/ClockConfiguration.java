package com.sfbank.bayanati.contactchannels.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single, injectable {@link Clock}, so {@code ContactChannelsService} never calls {@code
 * Instant.now()} directly — CLAUDE.md's business-logic testability rule requires a class in a
 * {@code service} package to be testable with no real clock, which means the clock must be a
 * dependency, not a static call.
 */
@Configuration
public class ClockConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
