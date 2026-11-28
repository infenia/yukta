// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.SingleServerLeader;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.worker.WorkerRegistration;
import com.infenia.yukta.service.worker.WorkerRegistry;
import com.infenia.yukta.startup.DefaultsAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.rsocket.server.RSocketServerCustomizer;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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
@AutoConfiguration(before = DefaultsAutoConfiguration.class)
@RegisterReflectionForBinding({
  WorkerFrame.class,
  NodeFrame.class,
  com.infenia.yukta.message.DefaultMessage.class
})
@SuppressWarnings("PMD.CouplingBetweenObjects") // wires both roles of the module
public class WorkersAutoConfiguration {

  /** Default constructor. */
  public WorkersAutoConfiguration() {
    super();
  }

  /**
   * Stops a native image from starting with worker settings it was not built for (see {@link
   * NativeRole}). Not conditional, so a native image of either role, or of none, has it.
   *
   * @param environment holds the worker settings
   * @param agent the worker's agent, when this image is a worker
   * @param executor the control plane's executor, when this image is a control plane
   * @return the check, run once every bean exists
   */
  @Bean
  public org.springframework.beans.factory.SmartInitializingSingleton workersNativeRoleCheck(
      final Environment environment,
      final ObjectProvider<WorkerAgent> agent,
      final ObjectProvider<WorkerNodeExecutor> executor) {
    return () ->
        NativeRole.check(
            org.springframework.core.NativeDetector.inNativeImage(),
            environment.getProperty("yukta.workers.control-plane", ""),
            environment.getProperty("yukta.workers.token", ""),
            agent.getIfAvailable() != null,
            executor.getIfAvailable() != null);
  }

  /**
   * Serves workers that register with the control plane.
   *
   * @param registry the workers that are connected
   * @param token the shared secret workers must present
   * @param address the interface the RSocket server listens on; empty means every interface
   * @param serverBundle the SSL bundle of the RSocket server; empty for plain TCP
   * @param allowInsecure allow plain TCP beyond this machine
   * @param environment holds {@code yukta.workers.tokens.<id>}, a secret per worker
   * @return the handler
   */
  @Bean
  @ConditionalOnExpression(
      "!'${yukta.workers.token:}'.isEmpty() && '${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerSessionHandler workerSessionHandler(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      final ObjectProvider<LeaderElection> election,
      @Value("${yukta.workers.token}") final String token,
      @Value("${spring.rsocket.server.address:}") final String address,
      @Value("${spring.rsocket.server.ssl.bundle:}") final String serverBundle,
      @Value("${yukta.workers.allow-insecure:false}") final boolean allowInsecure,
      final Environment environment) {
    WorkerTransport.requireTls(address, !serverBundle.isBlank(), allowInsecure);
    return new WorkerSessionHandler(
        registry,
        connections,
        election.getIfAvailable(SingleServerLeader::new),
        token,
        WorkerSessionHandler.tokensOf(environment));
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
   * @param tracker finds the observation of the node an attempt belongs to, when there is one
   * @param observations traces each attempt, when the application has a registry
   * @param meters times the attempts and their dispatch, when the application has a registry
   * @return the executor
   */
  @Bean
  @ConditionalOnExpression(
      "!'${yukta.workers.token:}'.isEmpty() && '${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerNodeExecutor remoteNodeExecutor(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      final ObjectMapper mapper,
      final ObjectProvider<TaskTrackerService> tracker,
      final ObjectProvider<ObservationRegistry> observations,
      final ObjectProvider<MeterRegistry> meters) {
    final TaskTrackerService runs = tracker.getIfAvailable();
    return new WorkerNodeExecutor(
        registry,
        connections,
        mapper,
        runs == null ? (executionId, nodeId) -> null : runs::observation,
        observations.getIfAvailable(() -> ObservationRegistry.NOOP),
        meters.getIfAvailable(SimpleMeterRegistry::new));
  }

  /**
   * The worker's runner of node attempts.
   *
   * @param plugins the plugins of this server
   * @param pluginTypes the plugin types this worker agreed to run, comma separated
   * @param mapper converts messages to and from plain JSON values
   * @param observations traces each attempt as a child of the control plane's span, when the
   *     application has a registry
   * @param capacity how many node attempts it runs at once (the same value the worker registers)
   * @return the runner
   */
  @Bean
  @ConditionalOnExpression("!'${yukta.workers.control-plane:}'.isEmpty()")
  public NodeRunner nodeRunner(
      final PluginRegistry plugins,
      @Value("${yukta.workers.plugin-types:}") final String pluginTypes,
      final ObjectMapper mapper,
      final ObjectProvider<ObservationRegistry> observations,
      @Value("${yukta.workers.capacity:4}") final int capacity) {
    return new NodeRunner(
        plugins,
        WorkerTransport.split(pluginTypes),
        mapper,
        observations.getIfAvailable(() -> ObservationRegistry.NOOP),
        capacity);
  }

  /**
   * Splits large messages to workers into fragments, limits the size of a message a worker sends
   * back (see {@link WorkerTransport#inboundLimit}) and counts the bytes both ways ({@link
   * WireMeter}).
   *
   * @param maxPayloadBytes {@code yukta.messaging.max-payload-bytes}
   * @param meters where the byte counts go, when the application has a registry
   * @return the customizer of the control plane's RSocket server
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.workers.token")
  @ConditionalOnExpression("'${yukta.workers.control-plane:}'.isEmpty()")
  public RSocketServerCustomizer workerFrameLimits(
      @Value("${yukta.messaging.max-payload-bytes:67108864}") final long maxPayloadBytes,
      final ObjectProvider<MeterRegistry> meters) {
    final WireMeter wire = new WireMeter(meters.getIfAvailable(SimpleMeterRegistry::new));
    return server ->
        server
            .fragment(WorkerTransport.FRAGMENT_BYTES)
            .maxInboundPayloadSize(WorkerTransport.inboundLimit(maxPayloadBytes))
            .interceptors(
                registry -> registry.forRequester(wire.requester()).forResponder(wire.responder()));
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
   * @param controlPlane {@code host:port} of the control plane's RSocket server; several, comma
   *     separated, are tried in turn until one is the leader
   * @param token the shared secret of the control plane
   * @param workerId this worker's id
   * @param pools the pools this worker serves, comma separated
   * @param pluginTypes the plugin types this worker may run, comma separated
   * @param capacity how many node attempts it runs at once
   * @param lease the control plane's lease; the agent renews three times per lease
   * @param drainTimeout longest the worker waits for running attempts when it stops
   * @param runner runs the attempts, and counts them
   * @param sslBundle name of the SSL bundle for TLS to the control plane; empty for plain TCP
   * @param allowInsecure allow plain TCP to a host that is not this machine
   * @param maxPayloadBytes {@code yukta.messaging.max-payload-bytes}, which limits the size of a
   *     message from the control plane
   * @param bundles the SSL bundles of the application
   * @return the agent, started and stopped (drained, without blocking) with the application
   */
  @Bean(destroyMethod = "")
  @SuppressWarnings({"PMD.UseObjectForClearerAPI", "PMD.ExcessiveParameterList"})
  @ConditionalOnExpression("!'${yukta.workers.control-plane:}'.isEmpty()")
  public WorkerAgent workerAgent(
      final RSocketRequester.Builder builder,
      final RSocketMessageHandler responder,
      final NodeRunner runner,
      @Value("${yukta.workers.control-plane}") final String controlPlane,
      @Value("${yukta.workers.token}") final String token,
      @Value("${yukta.workers.id}") final String workerId,
      @Value("${yukta.workers.pools:default}") final String pools,
      @Value("${yukta.workers.plugin-types:}") final String pluginTypes,
      @Value("${yukta.workers.capacity:4}") final int capacity,
      @Value("${yukta.workers.lease:15s}") final Duration lease,
      @Value("${yukta.workers.drain-timeout:30s}") final Duration drainTimeout,
      @Value("${yukta.workers.ssl-bundle:}") final String sslBundle,
      @Value("${yukta.workers.allow-insecure:false}") final boolean allowInsecure,
      @Value("${yukta.messaging.max-payload-bytes:67108864}") final long maxPayloadBytes,
      final ObjectProvider<SslBundles> bundles) {
    final SslBundle bundle = sslBundle.isBlank() ? null : bundles.getObject().getBundle(sslBundle);
    final int maxInbound = WorkerTransport.inboundLimit(maxPayloadBytes);
    // Several control planes (the leader and its standbys) may be listed; each connection attempt
    // tries the next one, and a standby refuses the worker, so the worker ends up on the leader.
    final List<Mono<RSocketRequester>> targets =
        Arrays.stream(controlPlane.split(","))
            .map(String::trim)
            .filter(address -> !address.isEmpty())
            .map(address ->
                WorkerTransport.target(
                    address, builder, responder, bundle, allowInsecure, maxInbound))
            .toList();
    final AtomicInteger turn = new AtomicInteger();
    // The control plane calls back into this connection to run nodes, so the worker answers
    // requests on it with its own handlers (the NodeRunner).
    final Mono<RSocketRequester> connect =
        Mono.defer(() -> targets.get(Math.floorMod(turn.getAndIncrement(), targets.size())));
    return new WorkerAgent(
        connect,
        token,
        new WorkerRegistration(
            workerId, WorkerTransport.split(pools), WorkerTransport.split(pluginTypes), capacity),
        lease.dividedBy(3),
        runner::running,
        drainTimeout);
  }
}
