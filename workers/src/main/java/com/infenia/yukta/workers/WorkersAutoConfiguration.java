// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.worker.WorkerRegistration;
import com.infenia.yukta.service.worker.WorkerRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Remote workers.
 *
 * <p>Control plane: set {@code yukta.workers.token} and {@code spring.rsocket.server.port}; workers
 * that present the token register on the route {@code worker.session}. Worker: set {@code
 * yukta.workers.control-plane} ({@code host:port}), {@code yukta.workers.token} and {@code
 * yukta.workers.id}; the agent connects out. A node is not run remotely yet.
 */
@AutoConfiguration
public class WorkersAutoConfiguration {

  /** Default constructor. */
  public WorkersAutoConfiguration() {
    super();
  }

  /**
   * Serves workers that register with the control plane.
   *
   * @param registry the workers that are connected
   * @param token the shared secret workers must present
   * @return the handler
   */
  @Bean
  @ConditionalOnExpression(
      "!'${yukta.workers.token:}'.isEmpty() && '${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerSessionHandler workerSessionHandler(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      @Value("${yukta.workers.token}") final String token) {
    return new WorkerSessionHandler(registry, connections, token);
  }

  /**
   * The way back into each connected worker.
   *
   * @return the connections
   */
  @Bean
  @ConditionalOnExpression(
      "!'${yukta.workers.token:}'.isEmpty() && '${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerConnections workerConnections() {
    return new WorkerConnections();
  }

  /**
   * Runs the nodes that have {@code engine.placement} on workers.
   *
   * @param registry the workers that are connected
   * @param connections the way back into each connected worker
   * @param mapper converts messages to and from plain JSON values
   * @return the executor
   */
  @Bean
  @ConditionalOnExpression(
      "!'${yukta.workers.token:}'.isEmpty() && '${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerNodeExecutor remoteNodeExecutor(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      final ObjectMapper mapper) {
    return new WorkerNodeExecutor(registry, connections, mapper);
  }

  /**
   * The worker's runner of node attempts.
   *
   * @param plugins the plugins of this server
   * @param pluginTypes the plugin types this worker agreed to run, comma separated
   * @param mapper converts messages to and from plain JSON values
   * @return the runner
   */
  @Bean
  @ConditionalOnExpression("!'${yukta.workers.control-plane:}'.isEmpty()")
  public NodeRunner nodeRunner(
      final PluginRegistry plugins,
      @Value("${yukta.workers.plugin-types:}") final String pluginTypes,
      final ObjectMapper mapper) {
    return new NodeRunner(plugins, split(pluginTypes), mapper);
  }

  /**
   * The RSocket route for workers.
   *
   * @param handler serves the connections
   * @return the controller
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.workers.token")
  @ConditionalOnExpression("'${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerController workerController(final WorkerSessionHandler handler) {
    return new WorkerController(handler);
  }

  /**
   * The agent of a worker: connects to the control plane and keeps its lease.
   *
   * @param builder builds the RSocket requester
   * @param responder lets the control plane call back into this worker
   * @param controlPlane {@code host:port} of the control plane's RSocket server
   * @param token the shared secret of the control plane
   * @param workerId this worker's id
   * @param pools the pools this worker serves, comma separated
   * @param pluginTypes the plugin types this worker may run, comma separated
   * @param capacity how many node attempts it runs at once
   * @param lease the control plane's lease; the agent renews three times per lease
   * @return the agent, started and stopped with the application
   */
  @Bean(initMethod = "start", destroyMethod = "stop")
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  @ConditionalOnExpression("!'${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerAgent workerAgent(
      final RSocketRequester.Builder builder,
      final RSocketMessageHandler responder,
      @Value("${yukta.workers.control-plane}") final String controlPlane,
      @Value("${yukta.workers.token}") final String token,
      @Value("${yukta.workers.id}") final String workerId,
      @Value("${yukta.workers.pools:default}") final String pools,
      @Value("${yukta.workers.plugin-types:}") final String pluginTypes,
      @Value("${yukta.workers.capacity:4}") final int capacity,
      @Value("${yukta.workers.lease:15s}") final Duration lease) {
    final int colon = controlPlane.lastIndexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException("yukta.workers.control-plane must be host:port");
    }
    // The control plane calls back into this connection to run nodes, so the worker answers
    // requests on it with its own handlers (the NodeRunner).
    final Mono<RSocketRequester> connect =
        builder
            .rsocketConnector(connector -> connector.acceptor(responder.responder()))
            .connectTcp(
                controlPlane.substring(0, colon),
                Integer.parseInt(controlPlane.substring(colon + 1)));
    return new WorkerAgent(
        connect,
        token,
        new WorkerRegistration(workerId, split(pools), split(pluginTypes), capacity),
        lease.dividedBy(3),
        () -> 0);
  }

  private static Set<String> split(final String csv) {
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
}
