// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.rsocket.transport.netty.client.TcpClientTransport;
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
