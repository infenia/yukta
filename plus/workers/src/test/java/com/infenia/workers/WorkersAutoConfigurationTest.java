// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
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
}
