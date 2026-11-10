// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Decides whether an http supervisor may post to a URL: the deployment's host lists ({@code
 * yukta.egress.*} and {@code yukta.http.allowed-hosts}) and, on top, no private, loopback or
 * link-local address unless the list names the host. Redirects are never followed, so the check
 * covers the only host that is called.
 */
public final class Egress {

  /** The setting that holds the HTTP host list, named in refusals. */
  private static final String PROPERTY = "yukta.http.allowed-hosts";

  /** First byte of a unique local IPv6 address, with the low bit masked off. */
  private static final int ULA_FIRST_BYTE = 0xFC;

  /** First byte of the shared address space 100.64.0.0/10. */
  private static final int CGNAT_FIRST_BYTE = 100;

  private final EgressPolicy policy;

  private final HostAllowList hosts;

  /**
   * Creates the check.
   *
   * @param policy the deployment, workflow and session host lists
   * @param hosts the HTTP host list
   */
  public Egress(final EgressPolicy policy, final HostAllowList hosts) {
    this.policy = policy;
    this.hosts = hosts;
  }

  /**
   * Checks a URL.
   *
   * @param url the URL about to be called
   * @return completes if the call is allowed; errors with {@link IllegalArgumentException} naming
   *     the reason
   */
  public Mono<Void> check(final String url) {
    return policy
        .check(url)
        .then(Mono.defer(() -> refuse(hosts.refusalFor(url, PROPERTY))))
        .then(
            Mono.fromCallable(() -> privateRefusal(url))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Egress::refuse));
  }

  private static Mono<Void> refuse(final @Nullable String reason) {
    return reason == null ? Mono.empty() : Mono.error(new IllegalArgumentException(reason));
  }

  /** Why the host resolves to an address inside the network, or null. */
  /* default */ @Nullable String privateRefusal(final String url) throws UnknownHostException {
    final String host = URI.create(url).getHost();
    if (host == null) {
      return "The URL has no host";
    }
    if (hosts.names(url)) {
      return null;
    }
    for (final InetAddress address : InetAddress.getAllByName(host)) {
      if (inside(address)) {
        return "Host '"
            + host
            + "' is a private, loopback or link-local address, refused unless it is listed in "
            + PROPERTY;
      }
    }
    return null;
  }

  /* default */ static boolean inside(final InetAddress address) {
    final byte[] raw = address.getAddress();
    return address.isLoopbackAddress()
        || address.isSiteLocalAddress()
        || address.isLinkLocalAddress()
        || address.isAnyLocalAddress()
        || address.isMulticastAddress()
        || (raw.length == 16 && (raw[0] & ULA_FIRST_BYTE) == ULA_FIRST_BYTE)
        || (raw.length == 4 && (raw[0] & 0xFF) == CGNAT_FIRST_BYTE && (raw[1] & 0xC0) == 0x40);
  }
}
