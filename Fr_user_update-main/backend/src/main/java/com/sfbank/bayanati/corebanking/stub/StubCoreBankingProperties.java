package com.sfbank.bayanati.corebanking.stub;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed data for {@link StubCoreBankingClient}, bound from {@code fru.core-banking.stub.*}.
 *
 * @param accounts the accounts the stub knows about, keyed by account number (the check carries no
 *     branch — OQ-024) and valued with the raw {@code Response_Code} to return: {@code 1} found,
 *     {@code -1} system error. Use bracket notation in properties files — {@code
 *     fru.core-banking.stub.accounts[0000000001]=1} — so Spring's relaxed binding does not rewrite
 *     the key or drop its leading zeros. Never real account numbers (CLAUDE.md hard rule)
 */
@ConfigurationProperties(prefix = "fru.core-banking.stub")
public record StubCoreBankingProperties(Map<String, Integer> accounts) {

  public StubCoreBankingProperties {
    accounts = accounts == null ? Map.of() : Map.copyOf(accounts);
  }
}
