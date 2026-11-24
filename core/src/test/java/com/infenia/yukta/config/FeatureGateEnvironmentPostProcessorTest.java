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
  private static final String SESSION_TYPE = "yukta.session.store-type";
  private static final String RESTART_DIR = "yukta.restart.dir";

  @ParameterizedTest
  @CsvSource({
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
  void noGatedSettings_orTheModuleIsPresent_orBlankSettings_areFine() {
    assertThatCode(
            () -> FeatureGateEnvironmentPostProcessor.check(new MockEnvironment(), name -> false))
        .doesNotThrowAnyException();
    assertThatCode(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment()
                    .withProperty(HA_MODE, POSTGRES)
                    .withProperty(SESSION_TYPE, POSTGRES),
                name -> true))
        .doesNotThrowAnyException();
    assertThatCode(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment()
                    .withProperty(RESTART_DIR, " ")
                    .withProperty(SESSION_TYPE, "in-memory")
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
                    .withProperty(SESSION_TYPE, " In-Memory ")
                    .withProperty("yukta.restart.store-type", "memory")
                    .withProperty("yukta.input.store-type", "MEMORY")
                    .withProperty("yukta.agents.store-type", "memory")
                    .withProperty(HA_MODE, "single"),
                name -> true))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "yukta.restart.store-type",
        "yukta.input.store-type",
        "yukta.agents.store-type",
        HA_MODE
      })
  void postgresSwitchWithoutPostgresSessions_stopsStartup(final String key) {
    assertThatThrownBy(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment().withProperty(key, " Postgres "), name -> true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(key + "=postgres needs yukta.session.store-type=postgres");
  }

  @ParameterizedTest
  @CsvSource({
    "yukta.session.store-type, file, yukta.session.store-type=file",
    "yukta.execution-log.dir, /tmp/l, yukta.execution-log.dir",
    "yukta.restart.dir, /tmp/r, yukta.restart.dir",
    "yukta.restart.spill-dir, /tmp/p, yukta.restart.spill-dir"
  })
  void removedFileSetting_stopsStartupAndPointsToPostgres(
      final String key, final String value, final String named) {
    assertThatThrownBy(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment().withProperty(key, value), name -> true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(named + " is no longer read")
        .hasMessageContaining("-Pyukta.persistence=postgres");
  }

  @Test
  void settingsThatDidNothingOnTheirOwn_areIgnored() {
    // the yukta-dogfood dev server sets the base directory while it keeps state in memory
    assertThatCode(() ->
            FeatureGateEnvironmentPostProcessor.check(
                new MockEnvironment()
                    .withProperty("yukta.session.base-dir", "/tmp/s")
                    .withProperty("yukta.execution-log.fsync", "true"),
                name -> false))
        .doesNotThrowAnyException();
  }

  @Test
  void onTheCoreOnlyClasspath_theHookUsesTheRealClassLookup() {
    final FeatureGateEnvironmentPostProcessor hook = new FeatureGateEnvironmentPostProcessor();

    assertThatCode(() -> hook.postProcessEnvironment(new MockEnvironment(), null))
        .doesNotThrowAnyException();
    assertThatThrownBy(() ->
            hook.postProcessEnvironment(
                new MockEnvironment().withProperty(RESTART_DIR, "/tmp/r"), null))
        .isInstanceOf(IllegalStateException.class);
  }
}
