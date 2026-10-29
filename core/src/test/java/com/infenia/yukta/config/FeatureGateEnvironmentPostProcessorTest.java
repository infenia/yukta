// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

@NoArgsConstructor
class FeatureGateEnvironmentPostProcessorTest {

  @ParameterizedTest
  @CsvSource({
    "yukta.session.store-type, file, yukta.session.store-type=file, -Pyukta.edition=plus",
    "yukta.execution-log.dir, /tmp/l, yukta.execution-log.dir, -Pyukta.edition=plus",
    "yukta.restart.dir, /tmp/r, yukta.restart.dir, -Pyukta.edition=plus",
    "yukta.session-memory.dir, /tmp/m, yukta.session-memory.dir, -Pyukta.edition=plus",
    "yukta.session.store-type, postgres, yukta.session.store-type=postgres,"
        + " -Pyukta.persistence=postgres",
    "yukta.restart.store-type, postgres, yukta.restart.store-type=postgres,"
        + " -Pyukta.persistence=postgres",
    "yukta.kv.store-type, postgres, yukta.kv.store-type=postgres, -Pyukta.persistence=postgres",
    "yukta.session-memory.store-type, postgres, yukta.session-memory.store-type=postgres,"
        + " -Pyukta.persistence=postgres",
    "yukta.ha.mode, postgres, yukta.ha.mode=postgres, -Pyukta.persistence=postgres",
    "yukta.workers.token, secret, yukta.workers.token, -Pyukta.workers=true",
    "yukta.workers.control-plane, host:1, yukta.workers.control-plane, -Pyukta.workers=true"
  })
  void settingWithoutItsModuleStopsStartupAndSaysHowToBuildIt(
      final String key, final String value, final String named, final String hint) {
    assertThatThrownBy(
            () ->
                FeatureGateEnvironmentPostProcessor.check(
                    new MockEnvironment().withProperty(key, value), name -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(named)
        .hasMessageContaining("Plus")
        .hasMessageContaining(hint);
  }

  @Test
  void noGatedSettings_orTheModuleIsPresent_orPostgresDirectories_areFine() {
    assertThatCode(
            () -> FeatureGateEnvironmentPostProcessor.check(new MockEnvironment(), name -> false))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                FeatureGateEnvironmentPostProcessor.check(
                    new MockEnvironment().withProperty("yukta.ha.mode", "postgres"), name -> true))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                FeatureGateEnvironmentPostProcessor.check(
                    new MockEnvironment()
                        .withProperty("yukta.session-memory.dir", " ")
                        .withProperty("yukta.session.store-type", "in-memory")
                        .withProperty("yukta.ha.mode", ""),
                    name -> false))
        .doesNotThrowAnyException();
  }

  @Test
  void directoryWithPostgresStoreTypeIsGatedByThePostgresRowOnly() {
    final MockEnvironment env =
        new MockEnvironment()
            .withProperty("yukta.restart.dir", "/tmp/r")
            .withProperty("yukta.restart.store-type", "postgres");

    assertThatCode(
            () -> FeatureGateEnvironmentPostProcessor.check(env, name -> name.contains("postgres")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> FeatureGateEnvironmentPostProcessor.check(env, name -> false))
        .hasMessageContaining("yukta.restart.store-type=postgres");
  }

  @Test
  void onTheCoreOnlyClasspath_theHookUsesTheRealClassLookup() {
    final FeatureGateEnvironmentPostProcessor hook = new FeatureGateEnvironmentPostProcessor();

    assertThatCode(() -> hook.postProcessEnvironment(new MockEnvironment(), null))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                hook.postProcessEnvironment(
                    new MockEnvironment().withProperty("yukta.restart.dir", "/tmp/r"), null))
        .isInstanceOf(IllegalStateException.class);
  }
}
