package com.sfbank.bayanati.corebanking.stub;

import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import com.sfbank.bayanati.corebanking.domain.CoreBankingClient;

/**
 * Stands in for the bank's core banking middleware in local development and every test.
 *
 * <p><strong>How a caller chooses which accounts return which outcome:</strong> the stub is a
 * lookup table supplied entirely by configuration, keyed by the account number alone (the check
 * carries no branch — OQ-024) and valued with the raw {@code Response_Code}. Add an entry returning
 * {@code 1} for a found account or {@code -1} to simulate the middleware's "System Error"; anything
 * absent from the table returns {@code 0}, "Account not Found".
 *
 * <p>Unknown-means-not-found is the truthful default, not a convenience: it is what the real
 * middleware does with an account it has never heard of (observed live, S1-04). It also means the
 * not-found outcome is always reachable without seeding anything.
 *
 * <p>The result carries <strong>no raw exchange</strong>: there was no HTTP call, and inventing
 * request/response bytes would put a fabricated artifact into the audit trail. Against the stub,
 * {@code AccountCheckService} therefore writes the attempt event without artifacts.
 *
 * <p>This class is selected by configuration and never by a runtime branch — there is no {@code if
 * (mock)} anywhere in the call path, and this class does not know a real implementation exists. See
 * {@code CoreBankingClientConfiguration}.
 */
public class StubCoreBankingClient implements CoreBankingClient {

  private final StubCoreBankingProperties properties;

  public StubCoreBankingClient(StubCoreBankingProperties properties) {
    this.properties = properties;
  }

  @Override
  public CoreBankingCheckResult check(String accountNumber) {
    return CoreBankingCheckResult.withoutExchange(
        properties.accounts().getOrDefault(accountNumber, CoreBankingCheckResult.NOT_FOUND));
  }
}
