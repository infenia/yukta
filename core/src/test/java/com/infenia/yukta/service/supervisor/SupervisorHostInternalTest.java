// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.session.InternalSessions;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Events of internal sessions reach a supervisor only when it asks and names the session. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ShortMethodName"})
class SupervisorHostInternalTest {

  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private final SessionConfigStore configs = mock(SessionConfigStore.class);

  private SupervisorHost supervisorHost;

  private static ExecutionSupervisor watcher(final List<String> seen) {
    return new ExecutionSupervisor() {
      @Override
      public String id() {
        return "w";
      }

      @Override
      public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
        return events
            .doOnNext(event -> seen.add(String.valueOf(event.sessionId())))
            .thenMany(Flux.empty());
      }
    };
  }

  private static ExecutionEvent in(final String session) {
    return new ExecutionEvent.WorkflowStatusChanged(
        "e-" + session, session, "wf", "RUNNING", Instant.now());
  }

  private SupervisorHost host() {
    when(configs.getTags("sys")).thenReturn(Mono.just(Map.of(InternalSessions.TAG, "true")));
    when(configs.getTags("main")).thenReturn(Mono.just(Map.of()));
    final SupervisorHost created =
        new SupervisorHost(
            providerOf(),
            feed,
            mock(DirectiveDispatcher.class),
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            false,
            20,
            1000);
    created.setInternalSessions(new InternalSessions(configs));
    created.start();
    supervisorHost = created;
    return created;
  }

  private void attach(
      final SupervisorHost created,
      final List<String> seen,
      final SupervisorScope scope,
      final boolean observe) {
    created.attach(
        new BoundSupervisor(
            new SupervisorBinding("b", "p", scope, true),
            watcher(seen),
            false,
            new SupervisorCapabilities(null, false, InputRisk.LOW),
            observe));
  }

  private void publishBoth() {
    feed.publish(in("sys"));
    feed.publish(in("main"));
  }

  @AfterEach
  void tearDown() {
    if (supervisorHost != null) {
      supervisorHost.stop();
    }
  }

  @Test
  void supervisorDoesNotSeeInternalSessionsByDefault() {
    final List<String> seen = new CopyOnWriteArrayList<>();
    attach(host(), seen, new SupervisorScope.Global(), false);

    Awaitility.await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              publishBoth();
              assertThat(seen).contains("main");
            });
    assertThat(seen).doesNotContain("sys");
  }

  @Test
  void eventsWithoutSessionAreNotHiddenAsInternal() {
    final List<String> seen = new CopyOnWriteArrayList<>();
    attach(host(), seen, new SupervisorScope.Global(), false);

    Awaitility.await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(
                  new ExecutionEvent.ControlOutcome(
                      "e-none",
                      null,
                      null,
                      "PauseWorkflowCommand",
                      null,
                      true,
                      null,
                      Instant.now()));
              assertThat(seen).isNotEmpty();
            });
    // The watcher records String.valueOf(sessionId), so a missing session shows as "null".
    assertThat(seen).contains("null");
  }

  @Test
  void observeInternalNeedsScopeThatNamesTheSession() {
    final List<String> global = new CopyOnWriteArrayList<>();
    final List<String> named = new CopyOnWriteArrayList<>();
    final SupervisorHost created = host();
    attach(created, global, new SupervisorScope.Global(), true);
    created.attach(
        new BoundSupervisor(
            new SupervisorBinding("b2", "p", new SupervisorScope.Session("sys"), true),
            watcher(named),
            false,
            new SupervisorCapabilities(null, false, InputRisk.LOW),
            true));

    Awaitility.await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              publishBoth();
              assertThat(named).contains("sys");
              assertThat(global).contains("main");
            });
    assertThat(global).doesNotContain("sys");
  }
}
