// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.rsocket.transport.netty.client.TcpClientTransport;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
@SuppressWarnings("PMD.LawOfDemeter")
class WorkerTransport {

  /**
   * Connects, with the worker's own handlers answering the control plane's requests on the same
   * connection.
   *
   * @param builder builds the requester
   * @param responder the worker's handlers
   * @param host the control plane's host
   * @param port the control plane's RSocket port
   * @param bundle the SSL bundle for TLS, or null for plain TCP
   * @return the connection, made when subscribed (again for every attempt)
   */
  /* default */ static Mono<RSocketRequester> connect(
      final RSocketRequester.Builder builder,
      final RSocketMessageHandler responder,
      final String host,
      final int port,
      @Nullable final SslBundle bundle) {
    final RSocketRequester.Builder configured =
        builder.rsocketConnector(connector -> connector.acceptor(responder.responder()));
    return bundle == null
        ? configured.connectTcp(host, port)
        : Mono.fromCallable(() -> secure(host, port, bundle))
            .flatMap(client -> configured.connect(TcpClientTransport.create(client)));
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
        .secure(
            spec ->
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
