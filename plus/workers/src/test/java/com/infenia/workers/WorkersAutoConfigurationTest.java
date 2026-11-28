// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.SingleServerLeader;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.worker.WorkerRegistration;
import com.infenia.yukta.service.worker.WorkerRegistry;
import com.infenia.yukta.service.worker.WorkerRegistryFixture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.messaging.rsocket.RSocketRequester;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class WorkersAutoConfigurationTest {

  private final WorkersAutoConfiguration config = new WorkersAutoConfiguration();

  private static ObjectProvider<MeterRegistry> meters() {
    final ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
    when(meters.getIfAvailable(ArgumentMatchers.any())).thenReturn(new SimpleMeterRegistry());
    return meters;
  }

  @Test
  void remoteNodeExecutorIsBuiltWithoutTracker() {
    final ObjectProvider<TaskTrackerService> tracker = mock(ObjectProvider.class);
    when(tracker.getIfAvailable()).thenReturn(null);
    final ObjectProvider<ObservationRegistry> observations = mock(ObjectProvider.class);
    when(observations.getIfAvailable(ArgumentMatchers.any())).thenReturn(ObservationRegistry.NOOP);

    final WorkerNodeExecutor executor =
        config.remoteNodeExecutor(
            mock(WorkerRegistry.class),
            new WorkerConnections(),
            mock(ObjectMapper.class),
            tracker,
            observations,
            meters());

    assertThat(executor).isNotNull();
    Mockito.verify(tracker).getIfAvailable();
  }

  @Test
  void remoteNodeExecutorUsesTheTrackerWhenPresent() {
    final TaskTrackerService runs = mock(TaskTrackerService.class);
    final ObjectProvider<TaskTrackerService> tracker = mock(ObjectProvider.class);
    when(tracker.getIfAvailable()).thenReturn(runs);
    final ObjectProvider<ObservationRegistry> observations = mock(ObjectProvider.class);
    when(observations.getIfAvailable(ArgumentMatchers.any())).thenReturn(ObservationRegistry.NOOP);

    final WorkerNodeExecutor executor =
        config.remoteNodeExecutor(
            mock(WorkerRegistry.class),
            new WorkerConnections(),
            mock(ObjectMapper.class),
            tracker,
            observations,
            meters());

    assertThat(executor).isNotNull();
    Mockito.verify(tracker).getIfAvailable();
  }

  @Test
  void remoteNodeExecutorFallsBackToNoopObservationsWithoutRegistry() {
    final ObjectProvider<TaskTrackerService> tracker = mock(ObjectProvider.class);
    final ObjectProvider<ObservationRegistry> observations = mock(ObjectProvider.class);
    when(observations.getIfAvailable(ArgumentMatchers.any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());

    final WorkerNodeExecutor executor =
        config.remoteNodeExecutor(
            mock(WorkerRegistry.class),
            new WorkerConnections(),
            mock(ObjectMapper.class),
            tracker,
            observations,
            meters());

    assertThat(executor).isNotNull();
    Mockito.verify(observations).getIfAvailable(ArgumentMatchers.any());
  }

  @Test
  void nodeRunnerFallsBackToNoopObservationsWithoutRegistry() {
    final ObjectProvider<ObservationRegistry> observations = mock(ObjectProvider.class);
    when(observations.getIfAvailable(ArgumentMatchers.any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());

    final NodeRunner runner =
        config.nodeRunner(
            mock(PluginRegistry.class), "", mock(ObjectMapper.class), observations, 2);

    assertThat(runner).isNotNull();
    Mockito.verify(observations).getIfAvailable(ArgumentMatchers.any());
  }

  @Test
  void workerSessionHandlerWithoutExecutor_registersTheWorkerWithoutWaking() {
    final WorkerRegistry registry =
        WorkerRegistryFixture.create(Duration.ofSeconds(15), new SimpleMeterRegistry());
    final ObjectProvider<LeaderElection> election = mock(ObjectProvider.class);
    when(election.getIfAvailable(ArgumentMatchers.any())).thenReturn(new SingleServerLeader());
    final ObjectProvider<WorkerNodeExecutor> executor = mock(ObjectProvider.class);
    when(executor.getIfAvailable()).thenReturn(null);

    final WorkerSessionHandler handler =
        config.workerSessionHandler(
            registry,
            new WorkerConnections(),
            election,
            "secret",
            "",
            "",
            true,
            new StandardEnvironment(),
            executor);

    StepVerifier.create(
            handler.session(
                mock(RSocketRequester.class),
                Flux.just(
                    WorkerFrame.register(
                        "secret",
                        new WorkerRegistration("w1", Set.of("default"), Set.of("T"), 2)))))
        .assertNext(frame -> assertThat(frame.type()).isEqualTo(WorkerFrame.Type.ACK))
        .verifyComplete();
  }
}
