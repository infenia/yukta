// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

import com.infenia.yukta.plugin.store.SecretContext;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.LinguisticNaming")
class EnvSecretProviderTest {

  private static final String DB_PASSWORD = "db.password";

  private final Map<String, String> env = Map.of("YUKTA_SECRET_DB_PASSWORD", "pw", "PATH", "/bin");

  private final EnvSecretProvider provider = new EnvSecretProvider(env::get);

  @Test
  void getSecret_readsPrefixedVariable_normalisingTheKey() {
    StepVerifier.create(provider.getSecret(DB_PASSWORD)).expectNext("pw").verifyComplete();
    StepVerifier.create(provider.getSecret("DB-PASSWORD")).expectNext("pw").verifyComplete();
  }

  @Test
  void getSecret_prefersTenantThenSessionThenGlobal() {
    final Map<String, String> scoped =
        Map.of(
            "YUKTA_SECRET_DB_PASSWORD", "global",
            "YUKTA_SECRET_S_S1__DB_PASSWORD", "session",
            "YUKTA_SECRET_T_ACME__DB_PASSWORD", "tenant");
    final EnvSecretProvider scopedProvider = new EnvSecretProvider(scoped::get);
    StepVerifier.create(
            scopedProvider.getSecret(DB_PASSWORD, new SecretContext("acme", "s1", "w", "n")))
        .expectNext("tenant")
        .verifyComplete();
    StepVerifier.create(
            scopedProvider.getSecret(DB_PASSWORD, new SecretContext(null, "s1", "w", "n")))
        .expectNext("session")
        .verifyComplete();
    StepVerifier.create(
            scopedProvider.getSecret(DB_PASSWORD, new SecretContext(null, "s2", "w", "n")))
        .expectNext("global")
        .verifyComplete();
  }

  @Test
  void getSecret_keyWithDoubleUnderscore_cannotNameAnotherScope() {
    final Map<String, String> scoped = Map.of("YUKTA_SECRET_S_S1__DB_PASSWORD", "session");
    StepVerifier.create(new EnvSecretProvider(scoped::get).getSecret("s_s1__db_password"))
        .verifyComplete();
  }

  @Test
  void getSecret_missingSecret_isEmpty() {
    StepVerifier.create(provider.getSecret("nope")).verifyComplete();
  }

  @Test
  void getSecret_cannotReachUnprefixedEnvironment() {
    StepVerifier.create(provider.getSecret("PATH")).verifyComplete();
  }

  @Test
  void defaultConstructor_usesProcessEnvironment() {
    StepVerifier.create(new EnvSecretProvider().getSecret("surely.not.set.anywhere"))
        .verifyComplete();
  }
}
