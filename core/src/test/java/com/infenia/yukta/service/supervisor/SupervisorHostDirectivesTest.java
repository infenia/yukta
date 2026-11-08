// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.DirectiveSupervisor;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.control.directive.CommandTarget;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.engine.SupervisorQueries;
import com.infenia.yukta.testing.Fixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Commands of an engine pass the directive gate before they are applied. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
class SupervisorHostDirectivesTest {

  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SupervisorHost host =
      SupervisorHostFixture.create(
          providerOf(),
          new ExecutionEventFeed(),
          dispatcher,
          new InMemoryExecutionLogStore(),
          meters,
          false,
          20,
          100);

  @AfterEach
  void tearDown() {
    host.stop();
  }

  private void attach(final SupervisorCapabilities capabilities, final Directive.Command... all) {
    attach(new SupervisorScope.Workflow("w", "s"), capabilities, all);
  }

  private void attach(
      final SupervisorScope scope,
      final SupervisorCapabilities capabilities,
      final Directive.Command... all) {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    when(dispatcher.target(any())).thenReturn(Mono.just(new CommandTarget("s", "w", null)));
    final DirectiveSupervisor supervisor =
        new DirectiveSupervisor() {
          @Override
          @SuppressWarnings("PMD.ShortMethodName")
          public String id() {
            return "unused";
          }

          @Override
          public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
            return Flux.just(all).concatWith(events.then(Mono.empty()));
          }
        };
    host.attach(
        Fixtures.boundSupervisor(
            new SupervisorAttachment("eng", "p", scope, true), supervisor, false, capabilities));
  }

  private long count(final String outcome) {
    final var counter =
        meters.find("yukta.supervisor.commands").tags("id", "eng", "outcome", outcome).counter();
    return counter == null ? 0 : (long) counter.count();
  }

  private static Directive.Command command(
      final String key, final Cause cause, final String reason) {
    return new Directive.Command(key, new PauseWorkflowCommand("e1"), cause, reason, null);
  }

  private static SupervisorCapabilities pausing(final int perMinute) {
    return new SupervisorCapabilities(
        null, false, InputRisk.LOW, Set.of(), Set.of("PauseWorkflowCommand"), perMinute);
  }

  @Test
  void reactionIsAppliedAndRepeatedKeyOnlyOnce() {
    attach(
        pausing(10), command("k", Cause.event("e1"), null), command("k", Cause.event("e1"), null));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("applied") == 1);
    verify(dispatcher, times(1)).dispatchOrdered(any());
  }

  @Test
  void anInitiativeNeedsReason() {
    attach(pausing(10), command("k", Cause.initiative(), " "));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("reason-required") == 1);
    assertThat(count("applied")).isZero();
  }

  @Test
  void anInitiativeNeedsAnEntryInTheInitiativeList() {
    attach(
        new SupervisorCapabilities(null, false, InputRisk.LOW),
        command("k", Cause.tick(), "level check"));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("initiative-not-allowed") == 1);
  }

  @Test
  void initiativeIsLimitedPerMinute() {
    attach(
        pausing(1),
        command("a", Cause.initiative(), "one"),
        command("b", Cause.initiative(), "two"));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("initiative-rate-limited") == 1);
    assertThat(count("applied")).isEqualTo(1);
  }

  @Test
  void beanSupervisorOfDirectivesIsGatedToo() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    when(dispatcher.target(any())).thenReturn(Mono.just(new CommandTarget("s", "w", null)));
    final DirectiveSupervisor bean =
        new DirectiveSupervisor() {
          @Override
          @SuppressWarnings("PMD.ShortMethodName")
          public String id() {
            return "eng";
          }

          @Override
          public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
            return Flux.just(command("k", Cause.initiative(), null))
                .concatWith(events.then(Mono.empty()));
          }
        };
    final SupervisorHost beans =
        SupervisorHostFixture.create(
            providerOf(bean),
            new ExecutionEventFeed(),
            dispatcher,
            new InMemoryExecutionLogStore(),
            meters,
            false,
            20,
            100);
    beans.start();
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("reason-required") == 1);
    beans.stop();
  }

  private SupervisorQueries statusIs(final String status) {
    final SupervisorQueries queries = mock(SupervisorQueries.class);
    when(queries.statusOf("s", "e1")).thenReturn(Mono.just(status));
    @SuppressWarnings("unchecked")
    final org.springframework.beans.factory.ObjectProvider<SupervisorQueries> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(queries);
    host.setQueries(provider);
    return queries;
  }

  @Test
  void commandWhoseIfStatusDoesNotMatchTheRunIsNotApplied() {
    statusIs("PAUSED");
    attach(
        pausing(10),
        new Directive.Command(
            "k", new PauseWorkflowCommand("e1"), Cause.event("e1"), null, "RUNNING"));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("precondition-failed") == 1);
    assertThat(count("applied")).isZero();
  }

  @Test
  void commandWhoseIfStatusMatchesIsAppliedAndPausedRunsAreHeld() {
    statusIs("running");
    attach(
        pausing(10),
        new Directive.Command(
            "k", new PauseWorkflowCommand("e1"), Cause.event("e1"), null, "RUNNING"));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("applied") == 1);
    assertThat(host.held("eng")).containsExactly("e1");
    assertThat(host.held("p")).containsExactly("e1");
  }

  @Test
  void ifStatusIsNotCheckedWithoutQueries() {
    attach(
        pausing(10),
        new Directive.Command(
            "k", new PauseWorkflowCommand("e1"), Cause.event("e1"), null, "RUNNING"));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("applied") == 1);
  }

  @Test
  void ifStatusIsNotCheckedForCommandOutsideAnySession() {
    final SupervisorQueries queries = statusIs("PAUSED");
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    when(dispatcher.target(any())).thenReturn(Mono.just(new CommandTarget(null, "w", null)));
    final DirectiveSupervisor supervisor =
        new DirectiveSupervisor() {
          @Override
          @SuppressWarnings("PMD.ShortMethodName")
          public String id() {
            return "unused";
          }

          @Override
          public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
            return Flux.just(
                    new Directive.Command(
                        "k", new PauseWorkflowCommand("e1"), Cause.event("e1"), null, "RUNNING"))
                .concatWith(events.then(Mono.empty()));
          }
        };
    host.attach(
        Fixtures.boundSupervisor(
            new SupervisorAttachment("eng", "p", new SupervisorScope.Workflow("w", null), true),
            supervisor,
            false,
            pausing(10)));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("applied") == 1);
    verify(queries, org.mockito.Mockito.never()).statusOf(any(), any());
  }

  @Test
  void attachedGlobalEngineOwnsNothing() {
    attach(new SupervisorScope.Global(), pausing(10), command("k", Cause.event("e1"), null));
    await().atMost(3, TimeUnit.SECONDS).until(() -> count("not-owned") == 1);
    verify(dispatcher, org.mockito.Mockito.never()).dispatchOrdered(any());
  }

  @Test
  void keyIsForgottenAfterThousandNewerOnes() {
    final Directive.Command[] all = new Directive.Command[1002];
    for (int i = 0; i <= 1000; i++) {
      all[i] = command("k" + i, Cause.event("e1"), null);
    }
    all[1001] = command("k0", Cause.event("e1"), null);
    attach(new SupervisorScope.Global(), pausing(10), all);
    await().atMost(10, TimeUnit.SECONDS).until(() -> count("not-owned") == 1002);
  }

  @Test
  void detachingClosesAnEngine() {
    final boolean[] closed = {false};
    final class Closing implements DirectiveSupervisor, AutoCloseable {
      @Override
      @SuppressWarnings("PMD.ShortMethodName")
      public String id() {
        return "unused";
      }

      @Override
      public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
        return Flux.never();
      }

      @Override
      public void close() {
        closed[0] = true;
      }
    }

    host.attach(
        Fixtures.boundSupervisor(
            new SupervisorAttachment("eng", "p", new SupervisorScope.Workflow("w", "s"), true),
            new Closing(),
            false,
            pausing(10)));
    assertThat(host.detach("eng")).isTrue();
    assertThat(closed[0]).isTrue();
  }

  @Test
  void anEngineThatFailsToCloseStillDetaches() {
    final class Failing implements DirectiveSupervisor, AutoCloseable {
      @Override
      @SuppressWarnings("PMD.ShortMethodName")
      public String id() {
        return "unused";
      }

      @Override
      public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
        return Flux.never();
      }

      @Override
      public void close() throws Exception {
        throw new java.io.IOException("stuck");
      }
    }

    host.attach(
        Fixtures.boundSupervisor(
            new SupervisorAttachment("eng", "p", new SupervisorScope.Workflow("w", "s"), true),
            new Failing(),
            false,
            pausing(10)));
    assertThat(host.detach("eng")).isTrue();
  }
}
