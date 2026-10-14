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
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
class ExposureGuardTest {

  /** Property that sets the listen address. */
  private static final String ADDRESS = "server.address";

  @Test
  void testLoopbackNeedsNoKey() {
    assertThatCode(
            () ->
                new ExposureGuard(new MockEnvironment().withProperty(ADDRESS, "127.0.0.1")).check())
        .doesNotThrowAnyException();
  }

  @Test
  void testUnknownHostWithoutKeyIsRefused() {
    assertThatThrownBy(
            () ->
                new ExposureGuard(
                        new MockEnvironment().withProperty(ADDRESS, "no-such-host.invalid"))
                    .check())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void testOpenBindWithoutKeyIsRefused() {
    assertThatThrownBy(
            () -> new ExposureGuard(new MockEnvironment().withProperty(ADDRESS, "0.0.0.0")).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("API key");
    assertThat(new MockEnvironment().getProperty(ADDRESS)).isNull();
    assertThatThrownBy(() -> new ExposureGuard(new MockEnvironment()).check())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void testOpenBindWithKeyStarts() {
    assertThatCode(
            () ->
                new ExposureGuard(
                        new MockEnvironment()
                            .withProperty(ADDRESS, "0.0.0.0")
                            .withProperty("yukta.api-key", "k"))
                    .check())
        .doesNotThrowAnyException();
  }
}
