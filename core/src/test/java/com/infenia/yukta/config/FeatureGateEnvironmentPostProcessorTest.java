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

  private static final String HA_MODE = "yukta.ha.mode";
  private static final String POSTGRES = "postgres";

  @ParameterizedTest
  @CsvSource({
    "yukta.session.store-type, file, yukta.session.store-type=file, -Pyukta.edition=plus",
    "yukta.execution-log.dir, /tmp/l, yukta.execution-log.dir, -Pyukta.edition=plus",
    "yukta.restart.dir, /tmp/r, yukta.restart.dir, -Pyukta.edition=plus",
    "yukta.session.store-type, postgres, yukta.session.store-type=postgres,"
        + " -Pyukta.persistence=postgres",
    "yukta.restart.store-type, postgres, yukta.restart.store-type=postgres,"
        + " -Pyukta.persistence=postgres",
    "yukta.input.store-type, postgres, yukta.input.store-type=postgres,"
        + " -Pyukta.persistence=postgres",
    "yukta.ha.mode, postgres, yukta.ha.mode=postgres, -Pyukta.persistence=postgres",
    "yukta.workers.token, secret, yukta.workers.token, -Pyukta.workers=true",
    "yukta.workers.control-plane, host:1, yukta.workers.control-plane, -Pyukta.workers=true"
  })
  void settingWithoutItsModuleStopsStartupAndSaysHowToBuildIt(
      final String key, final String value, final String named, final String hint) {
    assertThatThrownBy(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment().withProperty(key, value), name -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(named)
        .hasMessageContaining("Plus")
        .hasMessageContaining(hint);
  }

  @Test
  void valueIsMatchedLikeConditionalOnProperty_ignoringCaseAndBlanks() {
    assertThatThrownBy(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment().withProperty(HA_MODE, " Postgres "), name -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("yukta.ha.mode=postgres");
  }

  @Test
  void noGatedSettings_orTheModuleIsPresent_orPostgresDirectories_areFine() {
    assertThatCode(
            () -> FeatureGateEnvironmentPostProcessor.check(new MockEnvironment(), name -> false))
        .doesNotThrowAnyException();
    assertThatCode(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment().withProperty(HA_MODE, POSTGRES), name -> true))
        .doesNotThrowAnyException();
    assertThatCode(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment()
                    .withProperty("yukta.restart.dir", " ")
                    .withProperty("yukta.session.store-type", "in-memory")
                    .withProperty(HA_MODE, ""),
                name -> false))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({
    "yukta.session.store-type, postgre",
    "yukta.restart.store-type, pg",
    "yukta.input.store-type, durable",
    "yukta.agents.store-type, postgresql",
    "yukta.ha.mode, active"
  })
  void unknownStoreSwitchValue_stopsStartupInsteadOfSelectingTheDefault(
      final String key, final String value) {
    assertThatThrownBy(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment().withProperty(key, value), name -> true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(key + "=" + value)
        .hasMessageContaining(POSTGRES);
  }

  @Test
  void knownStoreSwitchValues_areAccepted_ignoringCaseAndBlanks() {
    assertThatCode(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment()
                    .withProperty("yukta.session.store-type", " In-Memory ")
                    .withProperty("yukta.restart.store-type", "memory")
                    .withProperty("yukta.input.store-type", "POSTGRES")
                    .withProperty("yukta.agents.store-type", "memory")
                    .withProperty(HA_MODE, "single"),
                name -> true))
        .doesNotThrowAnyException();
  }

  @Test
  void directoryWithPostgresStoreTypeIsGatedByThePostgresRowOnly() {
    final MockEnvironment env =
        new MockEnvironment()
            .withProperty("yukta.restart.dir", "/tmp/r")
            .withProperty("yukta.restart.store-type", POSTGRES);

    assertThatCode(
            () -> FeatureGateEnvironmentPostProcessor.check(env, name -> name.contains(POSTGRES)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> FeatureGateEnvironmentPostProcessor.check(env, name -> false))
        .hasMessageContaining("yukta.restart.store-type=postgres");
  }

  @Test
  void onTheCoreOnlyClasspath_theHookUsesTheRealClassLookup() {
    final FeatureGateEnvironmentPostProcessor hook = new FeatureGateEnvironmentPostProcessor();

    assertThatCode(() -> hook.postProcessEnvironment(new MockEnvironment(), null))
        .doesNotThrowAnyException();
    assertThatThrownBy(() ->
            hook.postProcessEnvironment(
                new MockEnvironment().withProperty("yukta.restart.dir", "/tmp/r"), null))
        .isInstanceOf(IllegalStateException.class);
  }
}
