// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.ShortMethodName"})
class CompositeSupervisorTest {

  private static final ExecutionControlCommand PAUSE =
      new ExecutionControlCommand.PauseWorkflowCommand("e1");
  private static final ExecutionControlCommand STOP =
      new ExecutionControlCommand.StopWorkflowCommand("e1", null, null);
  private static final ExecutionEvent EVENT =
      new ExecutionEvent.NodeStatusChanged(
          "e1", "s", "wf", "n", "echo", "FAILURE", null, Instant.EPOCH);

  private static ControlPlanePlugin plane(final ControlDecision decision) {
    return new ControlPlanePlugin() {
      @Override
      public String id() {
        return "plane";
      }

      @Override
      public Mono<ControlDecision> decide(final ExecutionEvent event) {
        return decision == null ? Mono.empty() : Mono.just(decision);
      }
    };
  }

  private static ControlDecision of(final ExecutionControlCommand... commands) {
    return new ControlDecision(List.of(commands), 1, ControlDecision.Source.RULE);
  }

  private static ExecutionSupervisor emitting(final ExecutionControlCommand command) {
    return new ExecutionSupervisor() {
      @Override
      public String id() {
        return "emit";
      }

      @Override
      public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
        return events.map(_ -> command);
      }
    };
  }

  private static ExecutionSupervisor reviewing(final Function<ControlRequest, Mono<Decision>> f) {
    return new ExecutionSupervisor() {
      @Override
      public String id() {
        return "rev";
      }

      @Override
      public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
        return Flux.empty();
      }

      @Override
      public Mono<Decision> review(final ControlRequest request) {
        return f.apply(request);
      }
    };
  }

  private static CompositeSupervisor composite(
      final CompositeMode mode,
      final ReviewMode review,
      final int quorum,
      final Clock clock,
      final ExecutionSupervisor... children) {
    final List<String> ids =
        java.util.stream.IntStream.range(0, children.length).mapToObj(i -> "c" + i).toList();
    return new CompositeSupervisor(
        "comp",
        new CompositeSettings(mode, ids, review, quorum, Duration.ofSeconds(1)),
        ids,
        List.of(children),
        clock);
  }

  private static CompositeSupervisor composite(
      final CompositeMode mode, final ExecutionSupervisor... children) {
    return composite(mode, ReviewMode.ALL, 2, Clock.systemUTC(), children);
  }

  @Test
  void chainTakesTheFirstNonEmptyDecision() {
    final var c =
        composite(CompositeMode.CHAIN, plane(of()), plane(null), plane(of(PAUSE)), plane(of(STOP)));
    StepVerifier.create(c.supervise(Flux.just(EVENT))).expectNext(PAUSE).verifyComplete();
  }

  @Test
  void firstMatchStopsAtAnEmptyDecision() {
    final var c = composite(CompositeMode.FIRST_MATCH, plane(null), plane(of()), plane(of(PAUSE)));
    StepVerifier.create(c.supervise(Flux.just(EVENT))).verifyComplete();
  }

  @Test
  void parallelMergesEveryChild() {
    final var c = composite(CompositeMode.PARALLEL, emitting(PAUSE), emitting(STOP));
    StepVerifier.create(c.supervise(Flux.just(EVENT)).collectList())
        .assertNext(list -> assertThat(list).containsExactlyInAnyOrder(PAUSE, STOP))
        .verifyComplete();
  }

  @Test
  void quorumSendsOnceWhenEnoughChildrenAgree() {
    final var c =
        composite(
            CompositeMode.QUORUM,
            ReviewMode.ALL,
            2,
            Clock.systemUTC(),
            emitting(PAUSE),
            emitting(PAUSE),
            emitting(STOP));
    StepVerifier.create(c.supervise(Flux.just(EVENT))).expectNext(PAUSE).verifyComplete();
  }

  @Test
  void quorumIgnoresALoneCommand() {
    final var c = composite(CompositeMode.QUORUM, emitting(PAUSE), emitting(STOP));
    StepVerifier.create(c.supervise(Flux.just(EVENT))).verifyComplete();
  }

  @Test
  void reviewAllNamesTheFirstDenyingChild() {
    final var c =
        composite(
            CompositeMode.PARALLEL,
            reviewing(_ -> Mono.just(Decision.ALLOW)),
            reviewing(_ -> Mono.just(Decision.deny("no"))));
    StepVerifier.create(c.review(new ControlRequest(PAUSE, "a", "s", "wf")))
        .assertNext(d -> assertThat(d).isEqualTo(Decision.deny("c1: no")))
        .verifyComplete();
  }

  @Test
  void reviewAnyAndMajority() {
    final var allow = reviewing(_ -> Mono.just(Decision.ALLOW));
    final var deny = reviewing(_ -> Mono.just(Decision.deny("no")));
    final ControlRequest request = new ControlRequest(PAUSE, "a", "s", "wf");
    StepVerifier.create(
            composite(CompositeMode.PARALLEL, ReviewMode.ANY, 2, Clock.systemUTC(), deny, allow)
                .review(request))
        .expectNext(Decision.ALLOW)
        .verifyComplete();
    StepVerifier.create(
            composite(
                    CompositeMode.PARALLEL,
                    ReviewMode.MAJORITY,
                    2,
                    Clock.systemUTC(),
                    deny,
                    allow,
                    deny)
                .review(request))
        .assertNext(d -> assertThat(d.allowed()).isFalse())
        .verifyComplete();
  }

  @Test
  void reviewErrorDeniesButAStopSurvivesATimeout() {
    final var broken = reviewing(_ -> Mono.error(new IllegalStateException("x")));
    final var hung = reviewing(_ -> Mono.never());
    StepVerifier.create(
            composite(CompositeMode.PARALLEL, broken)
                .review(new ControlRequest(PAUSE, "a", "s", "wf")))
        .assertNext(d -> assertThat(d.allowed()).isFalse())
        .verifyComplete();
    StepVerifier.withVirtualTime(
            () ->
                composite(CompositeMode.PARALLEL, hung)
                    .review(new ControlRequest(STOP, "a", "s", "wf")))
        .thenAwait(Duration.ofSeconds(5))
        .expectNext(Decision.ALLOW)
        .verifyComplete();
  }

  @Test
  void settingsAreChecked() {
    assertThatThrownBy(() -> settings(Map.of("mode", "NOPE", "children", List.of("a"))))
        .hasMessageContaining("mode");
    assertThatThrownBy(() -> settings(Map.of("mode", "PARALLEL", "children", List.of())))
        .hasMessageContaining("children");
    assertThatThrownBy(
            () -> settings(Map.of("mode", "QUORUM", "children", List.of("a", "b"), "quorum", 3)))
        .hasMessageContaining("quorum");
    assertThatThrownBy(
            () ->
                settings(
                    Map.of(
                        "mode",
                        "PARALLEL",
                        "children",
                        java.util.stream.IntStream.range(0, 11).mapToObj(i -> "c" + i).toList())))
        .hasMessageContaining("at most");
    assertThat(settings(Map.of("mode", "quorum", "children", List.of("a", "b", "c"))).quorum())
        .isEqualTo(2);
  }

  private static CompositeSettings settings(final Map<String, Object> map) {
    return CompositeSettings.of(new SupervisorProfile("p", "composite", map, false));
  }

  @Test
  void factoryRejectsCyclesDepthAndNonControlPlanes() {
    final SupervisorResolver resolver =
        new SupervisorResolver(
            new StaticListableBeanFactory()
                .getBeanProvider(com.infenia.yukta.plugin.control.SupervisorFactory.class));
    final var factories = new StaticListableBeanFactory();
    final CompositeSupervisorFactory factory =
        new CompositeSupervisorFactory(factoryProvider(resolver));
    final SupervisorProfile a = comp("a", "b");
    final SupervisorProfile b = comp("b", "a");
    resolver.refresh(List.of(a, b));
    assertThatThrownBy(() -> factory.create(a)).hasMessageContaining("cycle");

    final SupervisorProfile d1 = comp("d1", "d2");
    final SupervisorProfile d2 = comp("d2", "d3");
    final SupervisorProfile d3 = comp("d3", "d4");
    final SupervisorProfile d4 = comp("d4", "leaf");
    resolver.refresh(List.of(d1, d2, d3, d4));
    assertThatThrownBy(() -> factory.create(d1)).hasMessageContaining("nest at most");

    resolver.refresh(List.of(comp("x", "ghost")));
    assertThatThrownBy(() -> factory.create(comp("x", "ghost")))
        .hasMessageContaining("Unknown child");
    assertThat(factories).isNotNull();
    assertThat(resolver.compositesUsing("ghost")).containsExactly("x");
  }

  private static SupervisorProfile comp(final String id, final String child) {
    return new SupervisorProfile(
        id, "composite", Map.of("mode", "PARALLEL", "children", List.of(child)), false);
  }

  private static org.springframework.beans.factory.ObjectProvider<SupervisorResolver>
      factoryProvider(final SupervisorResolver resolver) {
    final var f = new StaticListableBeanFactory();
    f.addBean("r", resolver);
    return f.getBeanProvider(SupervisorResolver.class);
  }
}
