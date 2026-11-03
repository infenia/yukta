// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** One-time tokens: made for a request and a tier, checked in constant time, used once. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.HardCodedCryptoKey"})
class AnswerTokensTest {

  private final MutableClock clock = new MutableClock();
  private final AnswerTokens tokens = new AnswerTokens("a-long-test-secret", clock);

  private long expiry() {
    return clock.instant().plus(Duration.ofHours(1)).getEpochSecond();
  }

  @Test
  void tokenIsGoodForItsRequestTierAndExpiry() {
    final long expires = expiry();
    final String token = tokens.mint("r1", "ops", Instant.ofEpochSecond(expires));

    assertThat(tokens.verify("r1", "ops", expires, token)).isTrue();
  }

  @Test
  void anAlgorithmThatIsNotThereIsReportedNotHidden() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                AnswerTokens.hmac(
                    "NoSuchMac",
                    new javax.crypto.spec.SecretKeySpec(new byte[] {1}, "x"),
                    new byte[0]))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theSpringConstructorUsesTheSystemClock() {
    final AnswerTokens real = new AnswerTokens("secret");
    final Instant soon = Instant.now().plusSeconds(60);

    assertThat(real.verify("r1", "ops", soon.getEpochSecond(), real.mint("r1", "ops", soon)))
        .isTrue();
  }

  @Test
  void tokenIsNotGoodForAnythingElse() {
    final long expires = expiry();
    final String token = tokens.mint("r1", "ops", Instant.ofEpochSecond(expires));

    assertThat(tokens.verify("r2", "ops", expires, token)).isFalse();
    assertThat(tokens.verify("r1", "other", expires, token)).isFalse();
    assertThat(tokens.verify("r1", "ops", expires + 1, token)).isFalse();
    assertThat(tokens.verify("r1", "ops", expires, token.substring(1) + "A")).isFalse();
    assertThat(tokens.verify("r1", "ops", expires, "")).isFalse();
    assertThat(tokens.verify("r1", "ops", expires, "not base64 !!")).isFalse();
  }

  @Test
  void tokenMadeWithAnotherSecretIsRefused() {
    final long expires = expiry();
    final String foreign =
        new AnswerTokens("another-secret", clock).mint("r1", "ops", Instant.ofEpochSecond(expires));

    assertThat(tokens.verify("r1", "ops", expires, foreign)).isFalse();
  }

  @Test
  void tokenStopsWorkingAtItsExpiry() {
    final long expires = expiry();
    final String token = tokens.mint("r1", "ops", Instant.ofEpochSecond(expires));

    clock.advance(Duration.ofHours(1));
    assertThat(tokens.verify("r1", "ops", expires, token)).isTrue();
    clock.advance(Duration.ofSeconds(1));
    assertThat(tokens.verify("r1", "ops", expires, token)).isFalse();
  }

  @Test
  void withoutSecretEachServerMakesItsOwn() {
    final long expires = expiry();
    final String token =
        new AnswerTokens("", clock).mint("r1", "ops", Instant.ofEpochSecond(expires));
    final String blank =
        new AnswerTokens(" ", clock).mint("r1", "ops", Instant.ofEpochSecond(expires));
    final String unset =
        new AnswerTokens(null, clock).mint("r1", "ops", Instant.ofEpochSecond(expires));

    assertThat(tokens.verify("r1", "ops", expires, token)).isFalse();
    assertThat(token).isNotEqualTo(blank).isNotEqualTo(unset);
  }

  @Test
  void tokenCanBeUsedOnceUntilItIsGivenBack() {
    final long expires = expiry();

    assertThat(tokens.claim("r1", "ops", expires)).isTrue();
    assertThat(tokens.claim("r1", "ops", expires)).isFalse();
    assertThat(tokens.claim("r1", "other", expires)).isTrue();

    tokens.release("r1", "ops");
    assertThat(tokens.claim("r1", "ops", expires)).isTrue();
  }

  @Test
  void usedMarkersAreForgottenOnceTheTokenWouldHaveExpired() {
    final long expires = expiry();
    tokens.claim("r1", "ops", expires);

    clock.advance(Duration.ofHours(2));

    // the token is dead anyway, and the marker is dropped so the memory does not grow
    assertThat(tokens.claim("r1", "ops", expires)).isTrue();
  }
}
