// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One-time answer tokens for external responders: {@code base64url(HMAC-SHA256(secret, requestId |
 * tier | expiry))}. The receiver of a delivery can answer with it without an API key, once, until
 * the tier's deadline.
 *
 * <p>The secret is {@code yukta.input.token-secret}. When it is not set a random one is made at
 * start-up, and tokens do not survive a restart. The secret is never logged. Tokens that were used
 * are remembered in memory until they would have expired.
 *
 * @since 0.1.0-alpha.4
 */
@Slf4j
@Component
public class AnswerTokens {

  /** The MAC algorithm. */
  private static final String ALGORITHM = "HmacSHA256";

  /** Length of a made-up secret, in bytes. */
  private static final int SECRET_BYTES = 32;

  /** Makes the secret when none is configured. */
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The secret key. */
  private final SecretKeySpec key;

  /** The time, replaceable in tests. */
  private final Clock clock;

  /** Tokens already used, by their identity, with the second they expire. */
  private final Map<String, Long> used = new ConcurrentHashMap<>();

  /**
   * Creates the tokens from the settings.
   *
   * @param secret {@code yukta.input.token-secret}, or empty to make a random one
   */
  @Autowired
  public AnswerTokens(@Value("${yukta.input.token-secret:}") final String secret) {
    this(secret, Clock.systemUTC());
  }

  /**
   * Creates the tokens with a given clock.
   *
   * @param secret the secret, or empty to make a random one
   * @param clock the time
   */
  public AnswerTokens(final String secret, final Clock clock) {
    this.clock = clock;
    final byte[] bytes;
    if (secret == null || secret.isBlank()) {
      bytes = new byte[SECRET_BYTES];
      RANDOM.nextBytes(bytes);
      log.atWarn()
          .log(
              "yukta.input.token-secret is not set: answer tokens use a random secret and do not"
                  + " survive a restart");
    } else {
      bytes = secret.getBytes(StandardCharsets.UTF_8);
    }
    this.key = new SecretKeySpec(bytes, ALGORITHM);
  }

  /**
   * Makes the token for one request in one tier.
   *
   * @param requestId the request
   * @param tier the tier it is delivered to
   * @param expires when the token stops working
   * @return the token
   */
  public String mint(final String requestId, final String tier, final Instant expires) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac(requestId, tier, expires.getEpochSecond()));
  }

  /**
   * Checks a token: made by this server for this request and tier, and not expired. The comparison
   * takes the same time whatever the token is.
   *
   * @param requestId the request
   * @param tier the tier
   * @param expiresAt the expiry second the token was made with
   * @param token the token to check
   * @return true if the token is good
   */
  public boolean verify(
      final String requestId, final String tier, final long expiresAt, final String token) {
    final byte[] given;
    try {
      given = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      return false;
    }
    final boolean same = MessageDigest.isEqual(mac(requestId, tier, expiresAt), given);
    return same && clock.instant().getEpochSecond() <= expiresAt;
  }

  /**
   * Takes the one use of a token.
   *
   * @param requestId the request
   * @param tier the tier
   * @param expiresAt the expiry second of the token
   * @return true if this call took the use; false if it was taken before
   */
  public boolean claim(final String requestId, final String tier, final long expiresAt) {
    final long now = clock.instant().getEpochSecond();
    used.values().removeIf(expiry -> expiry < now);
    return used.putIfAbsent(identity(requestId, tier), expiresAt) == null;
  }

  /**
   * Gives a use back, when the answer it was claimed for did not happen.
   *
   * @param requestId the request
   * @param tier the tier
   */
  public void release(final String requestId, final String tier) {
    used.remove(identity(requestId, tier));
  }

  private static String identity(final String requestId, final String tier) {
    return requestId + '|' + tier;
  }

  private byte[] mac(final String requestId, final String tier, final long expiresAt) {
    final String text = requestId + '|' + tier + '|' + expiresAt;
    return hmac(ALGORITHM, key, text.getBytes(StandardCharsets.UTF_8));
  }

  /** The MAC of some bytes; the algorithm is a parameter so a test can name one that is absent. */
  /* default */ static byte[] hmac(
      final String algorithm, final SecretKeySpec key, final byte[] text) {
    try {
      final Mac mac = Mac.getInstance(algorithm);
      mac.init(key);
      return mac.doFinal(text);
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is in every Java runtime
      throw new IllegalStateException("HMAC is not available", e);
    }
  }
}
