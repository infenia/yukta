// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Tests that a section supervisor sees only the events of its nodes. */
@NoArgsConstructor
class SupervisorHostSectionTest {

  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private SupervisorHost host;

  @RequiredArgsConstructor
  private static final class Watcher implements ExecutionSupervisor {
    private final String id;
    private final SupervisorScope scope;
    private final List<String> seen = new CopyOnWriteArrayList<>();

    @Override
    public String id() {
      return id;
    }

    @Override
    public SupervisorScope scope() {
      return scope;
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return events
          .doOnNext(event -> seen.add(event.executionId() + "/" + event.nodeId()))
          .thenMany(Flux.empty());
    }
  }

  @AfterEach
  void tearDown() {
    if (host != null) {
      host.stop();
    }
  }

  private static ExecutionEvent node(final String execution, final String node) {
    return new ExecutionEvent.NodeStatusChanged(
        execution, "s", "w", node, "m", "RUNNING", Map.of(), Instant.now());
  }

  @Test
  void eachSectionSupervisorSeesOnlyTheEventsOfItsNodes() {
    final Watcher build = new Watcher("build", SupervisorScope.parse("workflow:s/w//build.*"));
    final Watcher deploy = new Watcher("deploy", SupervisorScope.parse("workflow:s/w//deploy.*"));
    final Watcher workflow = new Watcher("workflow", SupervisorScope.parse("workflow:s/w"));
    host =
        new SupervisorHost(
            providerOf(build, deploy, workflow),
            feed,
            dispatcher,
            new InMemoryExecutionLogStore(),
            meters,
            false,
            20,
            10_000);
    host.start();

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(
                  new ExecutionEvent.WorkflowStatusChanged(
                      "e", "s", "w", "RUNNING", Instant.now()));
              feed.publish(node("e", "build.compile"));
              feed.publish(
                  new ExecutionEvent.LogLine(
                      "e", "s", "w", "deploy.prod", "x", LogLevel.INFO, Instant.now()));
              feed.publish(node("last", "build.test"));
              assertThat(build.seen).contains("last/build.test");
              assertThat(deploy.seen).contains("e/deploy.prod");
              assertThat(workflow.seen).contains("e/null", "last/build.test");
            });
    assertThat(build.seen).allMatch(seen -> seen.contains("/build."));
    assertThat(deploy.seen).allMatch(seen -> seen.endsWith("/deploy.prod"));
    assertThat(host.status())
        .filteredOn(info -> "build".equals(info.id()))
        .singleElement()
        .satisfies(
            info -> {
              assertThat(info.scope()).isEqualTo("SECTION");
              assertThat(info.scopeWorkflowId()).isEqualTo("w");
              assertThat(info.scopeNodes()).containsExactly("build.*");
            });
  }
}
