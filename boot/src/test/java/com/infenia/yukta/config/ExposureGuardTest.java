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

  /** Address that listens on every interface. */
  private static final String ALL_INTERFACES = "0.0.0.0";

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
            () ->
                new ExposureGuard(new MockEnvironment().withProperty(ADDRESS, ALL_INTERFACES))
                    .check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("the REST API and MCP have none");
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
                            .withProperty(ADDRESS, ALL_INTERFACES)
                            .withProperty("yukta.api-key", "k"))
                    .check())
        .doesNotThrowAnyException();
  }

  @Test
  void testMcpKeyAloneDoesNotOpenTheRestApi() {
    assertThatThrownBy(
            () ->
                new ExposureGuard(
                        new MockEnvironment()
                            .withProperty(ADDRESS, ALL_INTERFACES)
                            .withProperty("yukta.mcp.api-key", "k"))
                    .check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("the REST API has none");
  }

  @Test
  void testOpenMcpAloneIsRefusedEvenWhenRestHasKey() {
    final com.infenia.yukta.security.ApiAuthenticator mcpOpen =
        new com.infenia.yukta.security.ApiAuthenticator() {
          @Override
          public boolean isOpen(final Scope scope) {
            return scope == Scope.MCP;
          }

          @Override
          public boolean named() {
            return false;
          }

          @Override
          public com.infenia.yukta.security.ApiKeys.Match authenticate(
              final Scope scope, final org.springframework.http.HttpHeaders headers) {
            return null;
          }
        };

    assertThatThrownBy(
            () ->
                new ExposureGuard(
                        new MockEnvironment().withProperty(ADDRESS, ALL_INTERFACES), mcpOpen)
                    .check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MCP has none");
  }
}
