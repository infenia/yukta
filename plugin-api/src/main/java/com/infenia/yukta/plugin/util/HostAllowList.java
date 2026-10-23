// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p>Whatever the list says, a link-local or cloud metadata address (169.254.169.254, {@code
 * fe80::/10}, {@code metadata.google.internal}) is refused unless it is listed by name, so a URL
 * built from a message cannot reach the metadata service of the cloud the server runs in.
 */
@SuppressWarnings("PMD.OnlyOneReturn")
public final class HostAllowList {

  /** A host written only with the characters of an IP address: digits, dots, colons, hex. */
  private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9a-fx.:]+");

  /** An IPv6 address in fe80::/10: the first ten bits are 1111111010. */
  private static final Pattern IPV6_LINK_LOCAL = Pattern.compile("fe[89ab][0-9a-f]?:[0-9a-f:.]*");

  /**
   * User info, host and port of an authority; the host is a name, or an IPv6 address in brackets.
   */
  private static final Pattern AUTHORITY =
      Pattern.compile("(?:[^@]*@)?(\\[[^\\]]+]|[^:\\[\\]]+)(?::\\d*)?");

  /** An IPv4 address has at most this many dot-separated parts. */
  private static final int MAX_ADDRESS_PARTS = 4;

  /** The first two bytes of 169.254.0.0/16 as a number. */
  private static final long LINK_LOCAL_PREFIX = 0xA9FEL;

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
    final String host = hostOf(url);
    if (host != null && !hosts.contains(host) && isMetadataAddress(host)) {
      return "Host '"
          + host
          + "' is a link-local or cloud metadata address, refused unless it is listed in "
          + property;
    }
    if (isEmpty()) {
      return null;
    }
    if (host == null) {
      return "The URL has no host, so it cannot be checked against " + property;
    }
    final boolean allowed = hosts.contains(host) || suffixes.stream().anyMatch(host::endsWith);
    return allowed ? null : "Host '" + host + "' is not in " + property;
  }

  /**
   * Whether a host is a link-local address (169.254.0.0/16, fe80::/10), where cloud metadata
   * services live, or a known metadata name. Numeric forms such as {@code 2852039166} and {@code
   * 0xA9FEA9FE} are recognised too. Nothing is resolved: only literals and the one known name.
   */
  private static boolean isMetadataAddress(final String host) {
    boolean metadata = "metadata.google.internal".equals(host);
    if (!metadata) {
      metadata =
          IPV6_LINK_LOCAL.matcher(host).matches()
              || NUMERIC_HOST.matcher(host).matches() && inetAton(host) >>> 16 == LINK_LOCAL_PREFIX;
    }
    return metadata;
  }

  /**
   * Reads an IPv4 address the way the C library does, which accepts 1 to 4 parts that are decimal,
   * octal ({@code 0251}) or hex ({@code 0xA9}); the last part fills the remaining bytes. Returns -1
   * if the text is not such an address.
   */
  private static long inetAton(final String host) {
    final String[] parts = host.split("\\.", -1);
    long address = -1;
    if (parts.length <= MAX_ADDRESS_PARTS) {
      long value = 0;
      boolean valid = true;
      for (int i = 0; i < parts.length && valid; i++) {
        final long number = number(parts[i]);
        final boolean last = i == parts.length - 1;
        final long max = last ? (1L << (8 * (5 - parts.length))) - 1 : 255;
        valid = number >= 0 && number <= max;
        value = last ? value | number : value | number << (8 * (3 - i));
      }
      address = valid ? value : -1;
    }
    return address;
  }

  private static long number(final String part) {
    long number = -1;
    try {
      if (part.startsWith("0x")) {
        number = Long.parseLong(part.substring(2), 16);
      } else if (part.length() > 1 && part.startsWith("0")) {
        number = Long.parseLong(part.substring(1), 8);
      } else if (!part.isEmpty()) {
        number = Long.parseLong(part);
      }
    } catch (NumberFormatException e) {
      number = -1;
    }
    return number;
  }

  private static @Nullable String hostOf(final String url) {
    try {
      final URI uri = URI.create(url.strip());
      final String host = uri.getHost() == null ? hostOfAuthority(uri) : uri.getHost();
      return host == null ? null : normalize(host);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * The host of an authority that {@link URI} does not accept as a host name, such as {@code
   * 169.254.43518} (a label that starts with a digit). HTTP clients and the C library still read it
   * as an address, so it must be checked rather than treated as having no host.
   */
  private static @Nullable String hostOfAuthority(final URI uri) {
    final String authority = uri.getRawAuthority();
    final Matcher match = authority == null ? null : AUTHORITY.matcher(authority);
    return match != null && match.matches() ? match.group(1) : null;
  }

  private static String normalize(final String host) {
    // lower case, without the brackets of an IPv6 address and without a trailing dot
    return host.strip().toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "").replaceAll("\\.$", "");
  }
}
