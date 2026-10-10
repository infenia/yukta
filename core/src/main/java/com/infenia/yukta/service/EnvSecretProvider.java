// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

import com.infenia.yukta.plugin.store.SecretProvider;
import java.util.Locale;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Default {@link SecretProvider}: reads secrets from environment variables.
 *
 * <p>The secret {@code db.password} (as in {@code decrypted:db.password}) is read from {@code
 * YUKTA_SECRET_DB_PASSWORD}. Only variables with that prefix are reachable, so a workflow cannot
 * use this to read arbitrary process environment. Provide your own {@link SecretProvider} bean to
 * use a vault instead.
 */
@Slf4j
@Service
public class EnvSecretProvider implements SecretProvider {

  /** Prefix of the environment variables that hold secrets. */
  /* default */ static final String PREFIX = "YUKTA_SECRET_";

  /** Looks up an environment variable by name. */
  private final UnaryOperator<String> env;

  /** Reads from the process environment. */
  @Autowired
  public EnvSecretProvider() {
    this(System::getenv);
  }

  /* default */ EnvSecretProvider(final UnaryOperator<String> env) {
    this.env = env;
  }

  @Override
  public Mono<String> getSecret(final String key) {
    final String name = PREFIX + key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    final String value = env.apply(name);
    if (value == null) {
      log.atWarn().log("Secret '{}' requested but environment variable {} is not set", key, name);
    }
    return Mono.justOrEmpty(value);
  }
}
