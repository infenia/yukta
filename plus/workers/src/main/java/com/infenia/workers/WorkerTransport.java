// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.rsocket.transport.netty.client.TcpClientTransport;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpClient;

/**
 * How a worker connects to the control plane: plain TCP, or TLS with an SSL bundle. With a bundle
 * the worker checks the control plane's certificate against the bundle's trust store, and presents
 * the bundle's key store certificate when the control plane asks for one (mutual TLS; the control
 * plane asks with {@code spring.rsocket.server.ssl.client-auth=need}). The host name in the
 * certificate is verified.
 */
@UtilityClass
class WorkerTransport {

  /**
   * Fragment size of both sides. An RSocket frame holds at most 16 MiB, so a larger message must be
   * split into fragments, or it cannot be sent at all.
   */
  /* default */ static final int FRAGMENT_BYTES = 64 * 1024;

  /** Largest RSocket frame: its length is 24 bits. */
  private static final int MAX_FRAME_BYTES = 0xFF_FFFF;

  /**
   * Largest message, reassembled from its fragments, that a side accepts from the other: twice the
   * messaging payload cap plus 1 MiB, because a message crosses as JSON (base64 for byte arrays,
   * escapes, the envelope's fields), which is larger than the payload {@code
   * yukta.messaging.max-payload-bytes} counts. The cap is checked again on the message itself.
   * Never below the largest frame (16 MiB): RSocket refuses to start with a smaller limit.
   *
   * @param maxPayloadBytes {@code yukta.messaging.max-payload-bytes}; 0 or less means no limit
   * @return the limit for {@code maxInboundPayloadSize}
   */
  // ponytail: fixed 2x margin over the cap; measure the JSON size instead if it is ever too tight
  /* default */ static int inboundLimit(final long maxPayloadBytes) {
    return maxPayloadBytes <= 0
        ? Integer.MAX_VALUE
        : (int)
            Math.min(
                Integer.MAX_VALUE, Math.max(MAX_FRAME_BYTES, maxPayloadBytes * 2 + 1024 * 1024));
  }

  /**
   * Connects, with the worker's own handlers answering the control plane's requests on the same
   * connection.
   *
   * @param builder builds the requester
   * @param responder the worker's handlers
   * @param host the control plane's host
   * @param port the control plane's RSocket port
   * @param bundle the SSL bundle for TLS, or null for plain TCP
   * @param maxInbound largest reassembled message accepted from the control plane
   * @return the connection, made when subscribed (again for every attempt)
   */
  /* default */ static Mono<RSocketRequester> connect(
      final RSocketRequester.Builder builder,
      final RSocketMessageHandler responder,
      final String host,
      final int port,
      @Nullable final SslBundle bundle,
      final int maxInbound) {
    final RSocketRequester.Builder configured =
        builder.rsocketConnector(connector ->
            connector
                .acceptor(responder.responder())
                .fragment(FRAGMENT_BYTES)
                .maxInboundPayloadSize(maxInbound));
    return Mono.fromCallable(() ->
            configured.transport(
                bundle == null
                    ? TcpClientTransport.create(host, port)
                    : TcpClientTransport.create(secure(host, port, bundle))))
        .flatMap(WorkerTransport::opened);
  }

  /**
   * Opens the requester's connection now, so a refused or failed connection fails this attempt (and
   * the agent retries it) instead of the first request. A requester from {@code transport} connects
   * lazily; its {@code rsocketClient().source()} connects and caches the connection that the
   * requests then use.
   *
   * @param requester a requester that has not connected yet
   * @return the requester, once its connection is open
   */
  private static Mono<RSocketRequester> opened(final RSocketRequester requester) {
    return requester.rsocketClient().source().thenReturn(requester);
  }

  /**
   * Connects to one control plane given as {@code host:port}, refusing plain TCP off this machine
   * unless allowed.
   *
   * @param address the control plane's {@code host:port}
   * @param builder builds the RSocket requester
   * @param responder lets the control plane call back into the connection
   * @param bundle the SSL bundle for TLS, or null for plain TCP
   * @param allowInsecure allow plain TCP to a host that is not this machine
   * @param maxInbound largest reassembled message accepted from the control plane
   * @return the connection, made when subscribed
   */
  /* default */ static Mono<RSocketRequester> target(
      final String address,
      final RSocketRequester.Builder builder,
      final RSocketMessageHandler responder,
      @Nullable final SslBundle bundle,
      final boolean allowInsecure,
      final int maxInbound) {
    final int colon = address.lastIndexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException("yukta.workers.control-plane must be host:port");
    }
    final String host = address.substring(0, colon);
    requireTls(host, bundle != null, allowInsecure);
    return connect(
        builder,
        responder,
        host,
        Integer.parseInt(address.substring(colon + 1)),
        bundle,
        maxInbound);
  }

  /**
   * Splits a comma separated list, dropping blanks and repeats.
   *
   * @param csv the list
   * @return the distinct values
   */
  /* default */ static Set<String> split(final String csv) {
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Refuses plain TCP to or from a host that is not this machine: the shared token, the node
   * configs (with any literal secret) and every payload would cross the network in clear text.
   *
   * @param host the address to connect to or listen on; empty means every interface
   * @param tls whether the connection is protected by an SSL bundle
   * @param allowInsecure the operator's explicit opt-out ({@code yukta.workers.allow-insecure})
   * @throws IllegalStateException if the connection would be plain TCP off this machine
   */
  /* default */ static void requireTls(
      final String host, final boolean tls, final boolean allowInsecure) {
    if (!tls && !allowInsecure && !loopback(host)) {
      throw new IllegalStateException(
          "Worker traffic to "
              + (host.isBlank() ? "every interface" : host)
              + " would be plain TCP. Set an SSL bundle (see the remote workers page), or"
              + " yukta.workers.allow-insecure=true on a network you trust.");
    }
  }

  private static boolean loopback(final String host) {
    final boolean loopback;
    boolean found;
    try {
      found = !host.isBlank() && InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException e) {
      found = false; // an unresolvable name is not this machine
    }
    loopback = found;
    return loopback;
  }

  private static TcpClient secure(final String host, final int port, final SslBundle bundle)
      throws SSLException {
    final SslContext context = context(bundle);
    return TcpClient.create()
        .host(host)
        .port(port)
        .secure(spec ->
            spec.sslContext(context)
                .handlerConfigurator(handler -> verifyHostName(handler.engine())));
  }

  private static SslContext context(final SslBundle bundle) throws SSLException {
    return SslContextBuilder.forClient()
        .keyManager(bundle.getManagers().getKeyManagerFactory())
        .trustManager(bundle.getManagers().getTrustManagerFactory())
        .build();
  }

  private static void verifyHostName(final SSLEngine engine) {
    final SSLParameters parameters = engine.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    engine.setSSLParameters(parameters);
  }
}
