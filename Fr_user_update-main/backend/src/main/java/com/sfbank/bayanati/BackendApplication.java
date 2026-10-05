package com.sfbank.bayanati;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// S6-04: @EnableScheduling turns on Spring's @Scheduled support for the whole application. Its
// only user today is notification.scheduler.OutboxDispatchScheduler, which drains
// app.notification_outbox; it sits here rather than inside that feature package because
// scheduling is an application-wide capability, and a second scheduled job would otherwise have
// to reach into an unrelated feature's configuration to find it enabled.
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

  public static void main(String[] args) {
    SpringApplication.run(BackendApplication.class, args);
  }
}
