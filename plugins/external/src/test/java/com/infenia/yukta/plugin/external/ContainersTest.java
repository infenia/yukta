// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link Containers}. */
class ContainersTest {

  @Test
  void clientEnvForwardsOnlyTheRuntimeVariablesThatAreSet() {
    final Map<String, String> server = Map.of("HOME", "/home/x", "SECRET", "s");

    final Map<String, String> env = Containers.clientEnv(server::get);

    assertThat(env).containsEntry("HOME", "/home/x").doesNotContainKey("SECRET");
  }

  @Test
  void clientEnvIsEmptyWhenTheServerHasNoRuntimeVariables() {
    assertThat(Containers.clientEnv(name -> null)).isEmpty();
  }
}
