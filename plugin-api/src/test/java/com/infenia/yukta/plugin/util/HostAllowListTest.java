// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Which hosts an outbound call may reach. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidUsingHardCodedIP"})
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
  void nullEntriesAreIgnoredAndIpv6AddressesMatchWithOrWithoutBrackets() {
    assertThat(HostAllowList.from(java.util.Arrays.asList("good.com", null)).isEmpty()).isFalse();
    assertThat(refusal(List.of("[::1]"), "http://[::1]:8080/x")).isNull();
    assertThat(refusal(List.of("::1"), "http://[::1]/x")).isNull();
    assertThat(refusal(List.of("::1"), "http://[::2]/x")).isNotNull();
  }

  @Test
  void listedIpAddressMatchesOnlyItself() {
    final List<String> hosts = List.of("10.0.0.5");

    assertThat(refusal(hosts, "http://10.0.0.5:8080/x")).isNull();
    assertThat(refusal(hosts, "http://10.0.0.6/x")).isNotNull();
  }

  @Test
  void metadataAddressesAreRefusedEvenWithoutAnyList() {
    final HostAllowList none = HostAllowList.from(null);
    for (final String url :
        List.of(
            "http://169.254.169.254/latest/meta-data",
            "http://2852039166/",
            "http://0xA9FEA9FE/",
            "http://169.254.43518/", // three parts: the last fills two bytes
            "http://169.16680110/", // two parts
            "http://0251.0376.0251.0376/", // octal
            "http://0xa9.0xfe.0xa9.0xfe/", // hex
            "http://user@169.254.43518:80/", // user info and port around a host URI rejects
            "http://[fe80::1]/",
            "http://[febf::1]/",
            "http://[::ffff:169.254.169.254]/", // IPv4-mapped IPv6
            "http://[::ffff:a9fe:a9fe]/",
            "http://[fd00:ec2::254]/", // AWS IPv6 metadata
            "http://metadata.google.internal/computeMetadata/v1/")) {
      assertThat(none.refusalFor(url, "p")).as(url).contains("link-local or cloud metadata");
    }
    assertThat(none.refusalFor("http://a:b:c/", "p")).contains("no readable host");
    assertThat(HostAllowList.from(List.of("x.com")).refusalFor("http://a:b:c/", "p"))
        .contains("no host");
    assertThat(none.refusalFor("http://[::ffff:8.8.8.8]/", "p")).isNull(); // mapped, public
    assertThat(none.refusalFor("http://[::ffff:808:808]/", "p")).isNull();
    assertThat(none.refusalFor("http://[::ffff:1:2:3]/", "p")).isNull();
    assertThat(none.refusalFor("http://localhost:8080/", "p")).isNull();
    assertThat(none.refusalFor("http://example.com/", "p")).isNull();
    assertThat(none.refusalFor("http://1234abc.example.com/", "p")).isNull();
    // numeric-looking hosts that are not link-local or not addresses at all
    for (final String host :
        List.of(
            "10.0.0.1",
            "127.1",
            "256.1.1.1", // part out of range
            "1.2.3.4.5", // too many parts
            "1..2", // empty part
            "09.1.1.1", // 9 is not an octal digit
            "0x.1.1.1", // no hex digits
            "170.254.1.1",
            "169.255.1.1",
            "fec0::1", // site-local, not link-local
            "fe00::1")) {
      assertThat(
              none.refusalFor(
                  "http://" + (host.contains(":") ? "[" + host + "]" : host) + "/", "p"))
          .as(host)
          .isNull();
    }
  }

  @Test
  void metadataAddressMayBeAllowedByNameOnPurpose() {
    final HostAllowList listed = HostAllowList.from(List.of("169.254.169.254"));

    assertThat(listed.refusalFor("http://169.254.169.254/latest", "p")).isNull();
    assertThat(listed.refusalFor("http://169.254.169.253/", "p")).contains("link-local");
  }
}
