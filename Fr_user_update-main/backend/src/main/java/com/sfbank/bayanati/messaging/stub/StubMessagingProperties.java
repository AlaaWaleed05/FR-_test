package com.sfbank.bayanati.messaging.stub;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed data for {@link StubMessageSender}, bound from {@code fru.messaging.stub.*}.
 *
 * @param outcomes the destinations the stub has an opinion about, keyed by the raw {@code
 *     destination} string and valued with the {@link DispatchOutcome} to return. Use bracket
 *     notation so Spring's relaxed binding does not mangle a destination containing {@code +} or
 *     {@code @} — {@code fru.messaging.stub.outcomes[+249900000001]=REJECTED}. Any destination
 *     absent from this map returns {@link DispatchOutcome#ACCEPTED}, mirroring {@code
 *     StubCoreBankingProperties}' unknown-means-the-honest-default reasoning. SYNTHETIC VALUES ONLY
 *     (CLAUDE.md hard rule).
 * @param latencyMillis artificial delay before returning, for tests that need to assert on {@code
 *     latencyMillis} being nonzero. Defaults to {@code 0}.
 * @param recordSends whether {@link StubMessageSender} keeps every attempt in memory for {@link
 *     StubMessageSender#recordedSends()}. Defaults to {@code false} — {@code stub} is not test-only
 *     (it is the only accepted provider until a real gateway specification exists, S3-05), so an
 *     always-on recorder would grow without bound for the life of a real deployment. Set {@code
 *     true} only in a test context that reads {@code recordedSends()}.
 */
@ConfigurationProperties(prefix = "fru.messaging.stub")
public record StubMessagingProperties(
    Map<String, DispatchOutcome> outcomes, long latencyMillis, boolean recordSends) {

  public StubMessagingProperties {
    outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
  }
}
