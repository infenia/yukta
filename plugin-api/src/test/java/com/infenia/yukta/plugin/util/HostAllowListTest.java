// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Which hosts an outbound call may reach. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
class HostAllowListTest {

  /** The setting named in the messages. */
  private static final String PROPERTY = "yukta.http.allowed-hosts";

  private static String refusal(final List<String> hosts, final String url) {
    return HostAllowList.from(hosts).refusalFor(url, PROPERTY);
  }

  @Test
  void anEmptyListAllowsEveryHost() {
    assertThat(refusal(List.of(), "https://anything.example/x")).isNull();
    assertThat(refusal(List.of(" ", ""), "http://10.0.0.1/")).isNull();
    assertThat(HostAllowList.from(null).isEmpty()).isTrue();
  }

  @Test
  void hostMatchesExactlyAndWithoutRegardToCase() {
    final List<String> hosts = List.of("Example.COM");

    assertThat(refusal(hosts, "https://example.com/path?q=1")).isNull();
    assertThat(refusal(hosts, "HTTPS://EXAMPLE.com:8443/x")).isNull();
    assertThat(refusal(hosts, "https://example.com./x")).as("trailing dot").isNull();
    assertThat(refusal(hosts, "https://evil.com/x"))
        .isEqualTo("Host 'evil.com' is not in yukta.http.allowed-hosts");
    assertThat(refusal(hosts, "https://example.com.evil.com/x")).isNotNull();
    assertThat(refusal(hosts, "https://api.example.com/x")).isNotNull();
  }

  @Test
  void wildcardMatchesSubdomainsOnly() {
    final List<String> hosts = List.of("*.example.com");

    assertThat(refusal(hosts, "https://api.example.com/x")).isNull();
    assertThat(refusal(hosts, "https://a.b.example.com/x")).isNull();
    assertThat(refusal(hosts, "https://example.com/x")).isNotNull();
    assertThat(refusal(hosts, "https://notexample.com/x")).isNotNull();
  }

  @Test
  void userInfoInTheUrlDoesNotFoolTheCheck() {
    assertThat(refusal(List.of("good.com"), "https://good.com@evil.com/x"))
        .isEqualTo("Host 'evil.com' is not in yukta.http.allowed-hosts");
    assertThat(refusal(List.of("good.com"), "https://evil.com#@good.com/")).isNotNull();
  }

  @Test
  void urlWithoutHostIsRefusedWhenListIsSet() {
    for (final String url : List.of("not a url", "file:///etc/passwd", "http:///x", "")) {
      assertThat(refusal(List.of("good.com"), url)).as(url).contains("no host");
    }
  }

  @Test
  void listedIpAddressMatchesOnlyItself() {
    final List<String> hosts = List.of("10.0.0.5");

    assertThat(refusal(hosts, "http://10.0.0.5:8080/x")).isNull();
    assertThat(refusal(hosts, "http://10.0.0.6/x")).isNotNull();
  }
}
