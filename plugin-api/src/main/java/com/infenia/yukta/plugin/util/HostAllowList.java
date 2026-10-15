// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The hosts an outbound call may reach, set by the operator ({@code yukta.http.allowed-hosts},
 * {@code yukta.mcp-client.allowed-hosts}). An empty list allows every host, which keeps existing
 * workflows working; once a list is set, a URL whose host is not on it is refused before any
 * request is made, whatever a workflow author or a message puts into the URL.
 *
 * <p>An entry is a host name or IP address, compared without regard to case, or {@code
 * *.example.com}, which matches any subdomain but not {@code example.com} itself. The host is the
 * one the URL really addresses, so {@code https://good.com@evil.com/} is checked as {@code
 * evil.com}. The check works on the name in the URL: it does not stop a listed name that resolves
 * to an address you did not intend.
 */
@SuppressWarnings("PMD.OnlyOneReturn")
public final class HostAllowList {

  /** Exact hosts, lower case. */
  private final Set<String> hosts;

  /** Suffixes of the wildcard entries, such as {@code .example.com}. */
  private final List<String> suffixes;

  private HostAllowList(final Set<String> hosts, final List<String> suffixes) {
    this.hosts = hosts;
    this.suffixes = suffixes;
  }

  /**
   * Builds a list from the configured entries.
   *
   * @param entries host names, IP addresses or {@code *.domain} patterns; blanks are ignored
   * @return the list; empty if there are no entries
   */
  public static HostAllowList from(final @Nullable Collection<String> entries) {
    final List<String> cleaned =
        entries == null
            ? List.of()
            : entries.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(HostAllowList::normalize)
                .toList();
    return new HostAllowList(
        cleaned.stream().filter(entry -> !entry.startsWith("*.")).collect(Collectors.toSet()),
        cleaned.stream()
            .filter(entry -> entry.startsWith("*."))
            .map(entry -> entry.substring(1))
            .toList());
  }

  /**
   * Whether no host is listed, so every host is allowed.
   *
   * @return true if the list is empty
   */
  public boolean isEmpty() {
    return hosts.isEmpty() && suffixes.isEmpty();
  }

  /**
   * Why a URL may not be called.
   *
   * @param url the URL about to be called
   * @param property the setting that holds the list, named in the message
   * @return the reason, or null if the call is allowed
   */
  public @Nullable String refusalFor(final String url, final String property) {
    if (isEmpty()) {
      return null;
    }
    final String host = hostOf(url);
    if (host == null) {
      return "The URL has no host, so it cannot be checked against " + property;
    }
    final boolean allowed = hosts.contains(host) || suffixes.stream().anyMatch(host::endsWith);
    return allowed ? null : "Host '" + host + "' is not in " + property;
  }

  private static @Nullable String hostOf(final String url) {
    try {
      final String host = URI.create(url.strip()).getHost();
      return host == null || host.isBlank() ? null : normalize(host);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String normalize(final String host) {
    String normalized = host.strip().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }
}
