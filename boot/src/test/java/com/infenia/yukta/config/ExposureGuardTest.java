// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Tests for the behaviour described by {@link ExposureGuard}. */
@NoArgsConstructor
class ExposureGuardTest {

  @Test
  void testLoopbackNeedsNoKey() {
    assertThatCode(
            () ->
                new ExposureGuard(
                    new MockEnvironment().withProperty("server.address", "127.0.0.1")))
        .doesNotThrowAnyException();
  }

  @Test
  void testOpenBindWithoutKeyIsRefused() {
    assertThatThrownBy(
            () ->
                new ExposureGuard(new MockEnvironment().withProperty("server.address", "0.0.0.0")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("API key");
    assertThat(new MockEnvironment().getProperty("server.address")).isNull();
    assertThatThrownBy(() -> new ExposureGuard(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void testOpenBindWithAKeyStarts() {
    assertThatCode(
            () ->
                new ExposureGuard(
                    new MockEnvironment()
                        .withProperty("server.address", "0.0.0.0")
                        .withProperty("yukta.api-key", "k")))
        .doesNotThrowAnyException();
  }
}
