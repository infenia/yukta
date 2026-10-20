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
@SuppressWarnings("PMD.CommentRequired")
class PlusRequiredEnvironmentPostProcessorTest {

  @ParameterizedTest
  @CsvSource({
    "yukta.session.store-type, file, yukta.session.store-type=file",
    "yukta.execution-log.dir, /tmp/l, yukta.execution-log.dir",
    "yukta.restart.dir, /tmp/r, yukta.restart.dir",
    "yukta.session-memory.dir, /tmp/m, yukta.session-memory.dir"
  })
  void fileSettingsWithoutPlus_stopStartup(
      final String key, final String value, final String named) {
    assertThatThrownBy(
            () ->
                PlusRequiredEnvironmentPostProcessor.check(
                    new MockEnvironment().withProperty(key, value), false))
        .hasMessageContaining(named)
        .hasMessageContaining("Plus");
  }

  @Test
  void noFileSettings_orPlusPresent_orPostgres_orBlank_areFine() {
    assertThatCode(() -> PlusRequiredEnvironmentPostProcessor.check(new MockEnvironment(), false))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                PlusRequiredEnvironmentPostProcessor.check(
                    new MockEnvironment().withProperty("yukta.restart.dir", "/tmp/r"), true))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                PlusRequiredEnvironmentPostProcessor.check(
                    new MockEnvironment()
                        .withProperty("yukta.restart.dir", "/tmp/r")
                        .withProperty("yukta.restart.store-type", "postgres")
                        .withProperty("yukta.session-memory.dir", " ")
                        .withProperty("yukta.session.store-type", "postgres"),
                    false))
        .doesNotThrowAnyException();
  }

  @Test
  void withoutPlusOnTheClasspath_theHookStopsStartup() {
    final PlusRequiredEnvironmentPostProcessor hook = new PlusRequiredEnvironmentPostProcessor();

    assertThatCode(() -> hook.postProcessEnvironment(new MockEnvironment(), null))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                hook.postProcessEnvironment(
                    new MockEnvironment().withProperty("yukta.restart.dir", "/tmp/r"), null))
        .isInstanceOf(IllegalStateException.class);
  }
}
