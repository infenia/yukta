// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

import com.infenia.yukta.plugin.store.SecretContext;
import com.infenia.yukta.plugin.store.SecretProvider;
import java.util.ArrayList;
import java.util.List;
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
 * YUKTA_SECRET_DB_PASSWORD}. A run with a tenant or session looks first for {@code
 * YUKTA_SECRET_T_<TENANT>__DB_PASSWORD}, then {@code YUKTA_SECRET_S_<SESSION>__DB_PASSWORD}, then
 * the global name, so one session can hold its own value; a key containing {@code __} is refused.
 * Only variables with that prefix are reachable, so a workflow cannot use this to read arbitrary
 * process environment. Provide your own {@link SecretProvider} bean to use a vault instead.
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
    return getSecret(key, SecretContext.NONE);
  }

  @Override
  public Mono<String> getSecret(final String key, final SecretContext context) {
    if (key.contains("__")) {
      // "__" separates a scope from the key, so a key cannot name another scope's secret
      log.atWarn().log("Secret '{}' refused: a key may not contain '__'", key);
      return Mono.empty();
    }
    final String name = normalise(key);
    String value = null;
    String scope = "global";
    for (final String[] candidate : scopes(context)) {
      value = env.apply(PREFIX + candidate[0] + name);
      if (value != null) {
        scope = candidate[1];
        break;
      }
    }
    if (value == null) {
      log.atWarn().log("Secret '{}' requested but no environment variable holds it", key);
    } else {
      log.atInfo().log(
          "Secret '{}' read from {} scope (workflow={}, node={})",
          key,
          scope,
          context.workflowId(),
          context.nodeId());
    }
    return Mono.justOrEmpty(value);
  }

  /** Scopes to try, most specific first: variable-name infix and scope label. */
  private static List<String[]> scopes(final SecretContext context) {
    final List<String[]> result = new ArrayList<>();
    if (context.tenant() != null) {
      result.add(new String[] {"T_" + normalise(context.tenant()) + "__", "tenant"});
    }
    if (context.sessionId() != null) {
      result.add(new String[] {"S_" + normalise(context.sessionId()) + "__", "session"});
    }
    result.add(new String[] {"", "global"});
    return result;
  }

  private static String normalise(final String text) {
    return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
  }
}
