package com.sfbank.bayanati.corebanking.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.corebanking.config.CoreBankingClientConfigurationTestAccess;
import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The real middleware, called through the real adapter. Inert unless {@code
 * FRU_CORE_BANKING_LIVE_ENDPOINT} names the endpoint (configuration, never committed) and the
 * {@code live} group is enabled; excluded by default alongside {@code integration} in pom.xml.
 *
 * <p>Why this exists: a {@code MockRestServiceServer} test proves the adapter against what we
 * <em>believe</em> the middleware sends. This proves it against what the middleware <em>does</em>
 * send.
 *
 * <p><strong>All three Response_Codes are now covered against the live service.</strong> The three
 * methods below use fabricated account values for the two non-success answers observed at S1-04.
 * The {@code 1} "Account Found" path was unreachable until S7-12 (2026-09-06), because reaching it
 * needs an account that exists; the product owner supplied one at run time and {@link
 * #aRealAccountIsFound} closes it. That method carries its own environment gate, so the other three
 * still run when no account is supplied — and the number itself is never committed, printed or
 * asserted on.
 *
 * <p>Run: {@code FRU_CORE_BANKING_LIVE_ENDPOINT=<url> ./mvnw test -Dgroups=live
 * -Dexcluded.test.groups= -Dtest=HttpCoreBankingClientLiveTest}, optionally with {@code
 * FRU_CORE_BANKING_LIVE_ACCOUNT=<real account>} in the environment and nowhere else.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FRU_CORE_BANKING_LIVE_ENDPOINT", matches = "https://.+")
class HttpCoreBankingClientLiveTest {

  private HttpCoreBankingClient client() {
    CoreBankingHttpProperties properties =
        new CoreBankingHttpProperties(
            URI.create(System.getenv("FRU_CORE_BANKING_LIVE_ENDPOINT")),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10));
    return new HttpCoreBankingClient(
        CoreBankingClientConfigurationTestAccess.restClient(properties), properties);
  }

  @Test
  void aRepeatedDigitAccountIsNotFound() {
    CoreBankingCheckResult result = client().check("00000000");

    print("00000000", result);
    assertEquals(CoreBankingCheckResult.NOT_FOUND, result.code());
    assertEquals("Account not Found", result.message());
    assertEquals(200, result.exchange().httpStatus());
  }

  @Test
  void aTwentyDigitAccountIsNotFound() {
    CoreBankingCheckResult result = client().check("99999999999999999999");

    print("99999999999999999999", result);
    assertEquals(CoreBankingCheckResult.NOT_FOUND, result.code());
  }

  @Test
  void anEmptyAccountIsTheMiddlewaresSystemError() {
    // The web layer never lets an empty value through; this reaches the adapter directly to
    // observe the -1 path, exactly as S1-04's discovery did.
    CoreBankingCheckResult result = client().check("");

    print("<empty>", result);
    assertEquals(CoreBankingCheckResult.SYSTEM_ERROR, result.code());
    assertEquals("System Error", result.message());
    assertEquals(200, result.exchange().httpStatus());
  }

  /**
   * The {@code 1} "Account Found" path, against a REAL account the product owner supplies at run
   * time — the one answer the middleware has never given this codebase, because reaching it needs
   * an account that exists (S7-12, 2026-09-06).
   *
   * <p><strong>The account number is never printed, never asserted on, and never
   * committed.</strong> It arrives only as {@code FRU_CORE_BANKING_LIVE_ACCOUNT}, and this method
   * deliberately does not call {@link #print} — that helper echoes its input, which is exactly the
   * value CLAUDE.md's no-live-account-numbers rule forbids in any log. The 58-byte reply carries a
   * code and a message and no customer data, so the body itself is safe to show.
   *
   * <p>Gated on its own environment variable as well as the class-level endpoint gate: with the
   * endpoint set but no account supplied, the other three methods must still run.
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "FRU_CORE_BANKING_LIVE_ACCOUNT", matches = "\\d+")
  void aRealAccountIsFound() {
    String account = System.getenv("FRU_CORE_BANKING_LIVE_ACCOUNT");
    HttpCoreBankingClient client = client();

    // TWO calls, timed separately. The first carries JVM warm-up and a cold TLS handshake; the
    // second is what a running backend actually experiences. Reporting only the first would
    // misattribute warm-up to the middleware and make the 5 s connect timeout look marginal
    // when it is not -- or hide a genuinely slow endpoint behind "it was just warming up".
    long coldStart = System.nanoTime();
    CoreBankingCheckResult result = client.check(account);
    long coldMillis = (System.nanoTime() - coldStart) / 1_000_000;

    long warmStart = System.nanoTime();
    CoreBankingCheckResult warm = client.check(account);
    long warmMillis = (System.nanoTime() - warmStart) / 1_000_000;
    assertEquals(result.code(), warm.code(), "the same account answered differently twice");
    System.out.println("[live] cold=" + coldMillis + "ms warm=" + warmMillis + "ms");

    assertNotNull(result.exchange());
    System.out.println(
        "[live] input=<real account, withheld> http="
            + result.exchange().httpStatus()
            + " content-type="
            + result.exchange().responseMediaType()
            + " body="
            + new String(result.exchange().responseBody(), StandardCharsets.UTF_8));

    assertEquals(CoreBankingCheckResult.FOUND, result.code());
    assertEquals(200, result.exchange().httpStatus());
  }

  private static void print(String input, CoreBankingCheckResult result) {
    assertNotNull(result.exchange());
    String body = new String(result.exchange().responseBody(), StandardCharsets.UTF_8);
    assertTrue(body.length() < 200, "unexpectedly large body: " + body.length());
    System.out.println(
        "[live] input="
            + input
            + " http="
            + result.exchange().httpStatus()
            + " content-type="
            + result.exchange().responseMediaType()
            + " body="
            + body);
  }
}
