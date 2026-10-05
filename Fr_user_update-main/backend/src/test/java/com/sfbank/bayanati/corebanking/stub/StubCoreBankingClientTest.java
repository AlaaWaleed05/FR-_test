package com.sfbank.bayanati.corebanking.stub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The stub's whole contract: a configured lookup table keyed by account number, with unknown
 * meaning "Account not Found" — what the real middleware does (observed live, S1-04).
 */
class StubCoreBankingClientTest {

  private final StubCoreBankingClient client =
      new StubCoreBankingClient(
          new StubCoreBankingProperties(Map.of("0000000001", 1, "0000000002", -1)));

  @Test
  void aSeededFoundAccountReturns1() {
    assertEquals(CoreBankingCheckResult.FOUND, client.check("0000000001").code());
  }

  @Test
  void aSeededSystemErrorAccountReturnsMinus1() {
    assertEquals(CoreBankingCheckResult.SYSTEM_ERROR, client.check("0000000002").code());
  }

  @Test
  void anUnseededAccountReturns0NotFound() {
    assertEquals(CoreBankingCheckResult.NOT_FOUND, client.check("9999999999").code());
  }

  @Test
  void theStubNeverInventsARawExchange() {
    // There was no HTTP call, so there are no request/response bytes -- and inventing some would
    // put a fabricated artifact into the audit trail. The service writes no artifacts for null.
    assertNull(client.check("0000000001").exchange());
    assertNull(client.check("0000000001").message());
  }

  @Test
  void anEmptySeedMakesEveryAccountNotFoundRatherThanFailing() {
    StubCoreBankingClient empty =
        new StubCoreBankingClient(new StubCoreBankingProperties(Map.of()));

    assertEquals(CoreBankingCheckResult.NOT_FOUND, empty.check("0000000001").code());
  }

  @Test
  void anAbsentSeedMapBindsAsEmptyRatherThanNull() {
    // Spring binds a @ConfigurationProperties record with no matching properties by passing null.
    StubCoreBankingClient unconfigured =
        new StubCoreBankingClient(new StubCoreBankingProperties(null));

    assertEquals(CoreBankingCheckResult.NOT_FOUND, unconfigured.check("0000000001").code());
  }
}
