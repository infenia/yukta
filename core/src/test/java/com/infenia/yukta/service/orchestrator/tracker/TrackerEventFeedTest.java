// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.streaming.StatusHistoryCache;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.CommentRequired"})
@NoArgsConstructor
class TrackerEventFeedTest {

  @Test
  void events_nodeWorkflowAndLogChanges_arePublishedWithWorkflowId() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final DefaultTaskTrackerService tracker =
        new DefaultTaskTrackerService(
            Duration.ofSeconds(5),
            mock(StatusHistoryCache.class),
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            ObservationRegistry.NOOP,
            feed);
    tracker.init();
    final List<ExecutionEvent> seen = new CopyOnWriteArrayList<>();
    feed.events().subscribe(seen::add);

    StepVerifier.create(tracker.startWorkflow("e1", "s1", "wf1", List.of("a"))).verifyComplete();
    tracker.emitTaskStatusEvent("e1", "a", "echo", TaskStatus.RUNNING, Map.of("k", "v"));
    tracker.emitLogEvent("e1", "a", "hello");
    tracker.emitWorkflowStatusEvent("e1", TaskStatus.SUCCESS);

    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertThat(seen).hasSize(3));
    assertThat(seen)
        .anySatisfy(
            event ->
                assertThat(event)
                    .isInstanceOfSatisfying(
                        ExecutionEvent.NodeStatusChanged.class,
                        node -> {
                          assertThat(node.workflowId()).isEqualTo("wf1");
                          assertThat(node.status()).isEqualTo("RUNNING");
                          assertThat(node.metadata()).containsEntry("k", "v");
                        }))
        .anySatisfy(event -> assertThat(event).isInstanceOf(ExecutionEvent.LogLine.class))
        .anySatisfy(
            event ->
                assertThat(event)
                    .isInstanceOfSatisfying(
                        ExecutionEvent.WorkflowStatusChanged.class,
                        workflow -> assertThat(workflow.status()).isEqualTo("SUCCESS")));
  }
}
