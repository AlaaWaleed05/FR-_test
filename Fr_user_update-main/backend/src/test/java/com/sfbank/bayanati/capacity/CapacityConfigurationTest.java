package com.sfbank.bayanati.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * S9-08: asserts that every limit determining this system's published concurrent capacity is the
 * value this project chose, in the RUNNING container.
 *
 * <p><strong>Why this test exists, stated plainly, because it is unusual.</strong> A test that
 * asserts configuration equals configuration is normally worthless. This one is not, and the reason
 * is that these particular numbers were published to a bank. The hosting specification's AP-7
 * states our concurrent capacity as a connection count, a thread count and a failure time. Before
 * S9-08 not one of those three was set anywhere in this repository: 10 was HikariCP's default
 * {@code maxPoolSize}, 200 was Tomcat's default {@code threads.max}, 30 s was Hikari's default
 * {@code connectionTimeout}. A dependency upgrade that changed any of them would have changed what
 * we had told a bank our system does, and nothing would have failed or warned.
 *
 * <p>So this test is not checking that Spring can read a properties file. It is the alarm that
 * fires if our published capacity is ever altered by somebody else's default.
 *
 * <p><strong>As of this commit the hosting document does NOT yet carry these numbers.</strong> AP-7
 * still publishes the inherited 10 / 200 / 30 s that this commit replaces with 20 / 100 / 10 s;
 * correcting it is a later commit in S9-08, together with the measurement. That is said here rather
 * than written as an aspiration in the present tense, because the rule this test defends is that
 * changing a number here means changing that document too -- and right now the document is the side
 * that is behind.
 *
 * <p><strong>What it catches is narrower than it looks.</strong> Where the value we chose equals
 * the framework default -- {@code minimum-idle} (HikariConfig coerces an unset value up to the pool
 * size, so 20 comes back either way), {@code threads.min-spare}, {@code accept-count} and {@code
 * task.scheduling.pool.size} -- deleting the property changes nothing and these assertions still
 * pass. Those four catch an upgrade that MOVES a default; they cannot catch a deletion. The ones
 * that catch both are the ones where we deliberately departed: the pool size, the connection
 * timeout, {@code threads.max} and {@code max-connections}. The properties file's own block says
 * the same thing, so neither side claims a guard it does not have.
 *
 * <p><strong>It reads the live objects, not the Environment</strong> -- the actual Tomcat
 * connector's protocol handler, and the actual {@link HikariDataSource} the application will use.
 * Asserting {@code environment.getProperty(...)} would prove only that the text is in the file,
 * which is the half of the problem that was never in doubt. A misspelt property name -- the failure
 * mode that produces a silently-inherited default -- passes an Environment check and fails this
 * one. That is the whole point, and it is why this class boots a real web server ({@code
 * RANDOM_PORT}) instead of the mock one every other integration test here uses.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityConfigurationTest extends AbstractPostgresIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private TaskScheduler taskScheduler;

  @Test
  void connectionPoolIsTheSizeWeChoseAndNotHikarisDefault() {
    HikariDataSource pool = assertInstanceOf(HikariDataSource.class, dataSource);

    // 20, against a PostgreSQL max_connections budget of 100. Hikari's own default is 10; the
    // hosting document's AP-7 previously published that 10 as our design.
    assertEquals(20, pool.getMaximumPoolSize(), "spring.datasource.hikari.maximum-pool-size");

    // Fixed-size: minimum-idle equals maximum-pool-size, so the pool never pays connection
    // establishment (and, on AWS, a full TLS handshake under sslmode=verify-full) during a burst.
    assertEquals(20, pool.getMinimumIdle(), "spring.datasource.hikari.minimum-idle");

    // 10 s, not Hikari's 30 s. AP-7 published "fail after 30 seconds" as a design decision when it
    // was in fact this default.
    assertEquals(
        10_000L, pool.getConnectionTimeout(), "spring.datasource.hikari.connection-timeout");

    // 25 minutes, chosen to retire a connection before a 30-minute network idle timer can cut it.
    assertEquals(1_500_000L, pool.getMaxLifetime(), "spring.datasource.hikari.max-lifetime");
    assertEquals(120_000L, pool.getKeepaliveTime(), "spring.datasource.hikari.keepalive-time");
  }

  @Test
  void workerThreadLimitsAreOursAndNotTomcatsDefaults() {
    TomcatWebServer webServer =
        assertInstanceOf(
            TomcatWebServer.class,
            ((ServletWebServerApplicationContext) applicationContext).getWebServer());
    Connector connector = webServer.getTomcat().getConnector();
    AbstractProtocol<?> protocol =
        assertInstanceOf(AbstractProtocol.class, connector.getProtocolHandler());

    // 100, not Tomcat's 200. This limit is bounded by heap rather than by CPU. The properties file
    // carries the reasoning and the correction to it: the two result objects of one in-flight
    // upload are ~16 MB and ~12 MB, but the parser's intermediate buffers coexist with them, so the
    // true peak is materially higher and the number is justified by how implausible a hundred
    // simultaneous uploads are, not by dividing a heap figure. Deliberately not restated here --
    // an arithmetic claim maintained in two places is an arithmetic claim that will disagree.
    assertEquals(100, protocol.getMaxThreads(), "server.tomcat.threads.max");
    assertEquals(10, protocol.getMinSpareThreads(), "server.tomcat.threads.min-spare");

    // What actually happens past threads.max. AP-7 previously described this only as "further
    // requests queue", which is the behaviour of accept-count and was never stated as a number.
    assertEquals(100, protocol.getAcceptCount(), "server.tomcat.accept-count");

    // 1000, not Tomcat's 8192.
    assertEquals(1000, protocol.getMaxConnections(), "server.tomcat.max-connections");
  }

  @Test
  void schedulerRunsExactlyOneThreadSoOutboxTicksCannotOverlap() {
    // OutboxDispatchScheduler's javadoc records that the outbox design depends on ticks being
    // serialised. fixedDelay alone does not guarantee that -- it guarantees no overlap only while
    // the pool cannot run two ticks at once, which was resting on an unset property that happens
    // to default to 1. Raising spring.task.scheduling.pool.size for an unrelated future job would
    // have let two dispatcher passes run together, against a claim lease that does not survive it.
    ThreadPoolTaskScheduler scheduler =
        assertInstanceOf(
            ThreadPoolTaskScheduler.class,
            taskScheduler,
            "a SimpleAsyncTaskScheduler here would mean virtual threads were switched on, which"
                + " would also invalidate the worker-thread limits above");

    assertEquals(
        1,
        scheduler.getScheduledThreadPoolExecutor().getCorePoolSize(),
        "spring.task.scheduling.pool.size");
  }
}
