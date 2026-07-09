// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for {@link NoOpSecretProvider}. */
@NoArgsConstructor
class NoOpSecretProviderTest {

  @Test
  void testGetSecretReturnsEmpty() {
    final NoOpSecretProvider provider = new NoOpSecretProvider();
    StepVerifier.create(provider.getSecret("any-key")).verifyComplete();
  }
}
