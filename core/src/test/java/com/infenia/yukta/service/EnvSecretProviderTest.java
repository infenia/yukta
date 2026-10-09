// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.LinguisticNaming"})
class EnvSecretProviderTest {

  private final Map<String, String> env = Map.of("YUKTA_SECRET_DB_PASSWORD", "pw", "PATH", "/bin");

  private final EnvSecretProvider provider = new EnvSecretProvider(env::get);

  @Test
  void getSecret_readsPrefixedVariable_normalisingTheKey() {
    StepVerifier.create(provider.getSecret("db.password")).expectNext("pw").verifyComplete();
    StepVerifier.create(provider.getSecret("DB-PASSWORD")).expectNext("pw").verifyComplete();
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
