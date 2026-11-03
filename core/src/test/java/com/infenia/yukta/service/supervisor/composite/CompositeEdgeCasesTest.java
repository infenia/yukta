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
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ShortMethodName",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CommentDefaultAccessModifier",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
class CompositeEdgeCasesTest {

  private static final ExecutionControlCommand PAUSE =
      new ExecutionControlCommand.PauseWorkflowCommand("e1");
  private static final ExecutionEvent EVENT =
      new ExecutionEvent.NodeStatusChanged(
          "e1", "s", "wf", "n", "echo", "FAILURE", null, Instant.EPOCH);
  private static final ControlRequest REQUEST = new ControlRequest(PAUSE, "a", "s", "wf");

  /** A plain supervisor with a fixed order. */
  private static final class Plain implements ExecutionSupervisor {
    private final int order;
    private final Mono<Decision> review;

    Plain(final int order, final Mono<Decision> review) {
      this.order = order;
      this.review = review;
    }

    @Override
    public String id() {
      return "plain";
    }

    @Override
    public int order() {
      return order;
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return events.map(_ -> PAUSE);
    }

    @Override
    public Mono<Decision> review(final ControlRequest request) {
      return review;
    }
  }

  private static CompositeSettings quorumSettings(final int quorum) {
    return new CompositeSettings(
        CompositeMode.QUORUM,
        List.of("a", "b", "c"),
        ReviewMode.ALL,
        quorum,
        Duration.ofSeconds(1));
  }

  /** A clock the test moves. */
  private static final class Moving extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @Test
  void exposesIdChildrenAndLowestOrder() {
    final var composite =
        new CompositeSupervisor(
            "comp",
            quorumSettings(2),
            List.of("a", "b"),
            List.of(new Plain(5, Mono.empty()), new Plain(2, Mono.empty())),
            Clock.systemUTC());
    assertThat(composite.id()).isEqualTo("comp");
    assertThat(composite.childIds()).containsExactly("a", "b");
    assertThat(composite.order()).isEqualTo(2);
    assertThat(
            new CompositeSupervisor("x", quorumSettings(2), List.of(), List.of(), Clock.systemUTC())
                .order())
        .isZero();
  }

  @Test
  void emptyReviewsAllowAndAllDenyingAnyDenies() {
    final var empty = new Plain(0, Mono.empty());
    final var deny = new Plain(0, Mono.just(Decision.deny("no")));
    for (final ReviewMode mode : ReviewMode.values()) {
      final var settings =
          new CompositeSettings(
              CompositeMode.PARALLEL, List.of("a"), mode, 0, Duration.ofSeconds(1));
      StepVerifier.create(
              new CompositeSupervisor(
                      "c", settings, List.of("a"), List.of(empty), Clock.systemUTC())
                  .review(REQUEST))
          .expectNext(Decision.ALLOW)
          .verifyComplete();
    }
    final var any =
        new CompositeSettings(
            CompositeMode.PARALLEL, List.of("a"), ReviewMode.ANY, 0, Duration.ofSeconds(1));
    StepVerifier.create(
            new CompositeSupervisor("c", any, List.of("a"), List.of(deny), Clock.systemUTC())
                .review(REQUEST))
        .expectNext(Decision.deny("a: no"))
        .verifyComplete();
  }

  @Test
  void majorityAllowsWhenMoreThanHalfAllow() {
    final var allow = new Plain(0, Mono.just(Decision.ALLOW));
    final var deny = new Plain(0, Mono.just(Decision.deny("no")));
    final var settings =
        new CompositeSettings(
            CompositeMode.PARALLEL,
            List.of("a", "b", "c"),
            ReviewMode.MAJORITY,
            0,
            Duration.ofSeconds(1));
    StepVerifier.create(
            new CompositeSupervisor(
                    "c",
                    settings,
                    settings.children(),
                    List.of(allow, allow, deny),
                    Clock.systemUTC())
                .review(REQUEST))
        .expectNext(Decision.ALLOW)
        .verifyComplete();
  }

  @Test
  void quorumForgetsVotesAfterWindow() {
    final Moving clock = new Moving();
    final var voters =
        List.<ExecutionSupervisor>of(new Plain(0, Mono.empty()), new Plain(0, Mono.empty()));
    final var composite =
        new CompositeSupervisor("c", quorumSettings(2), List.of("a", "b"), voters, clock);
    // one child votes twice: still one vote
    final var twice =
        new CompositeSupervisor(
            "c", quorumSettings(2), List.of("a"), List.of(new Plain(0, Mono.empty())), clock);
    StepVerifier.create(twice.supervise(Flux.just(EVENT, EVENT))).verifyComplete();
    // second event arrives after the window, so the first votes are gone
    final var events =
        Flux.just(EVENT)
            .concatWith(
                Mono.fromRunnable(() -> clock.now.set(Instant.EPOCH.plusSeconds(5)))
                    .then(Mono.just(EVENT)));
    StepVerifier.create(composite.supervise(events).collectList())
        .assertNext(list -> assertThat(list).hasSize(2))
        .verifyComplete();
  }

  @Test
  void settingsRejectBadValues() {
    assertThatThrownBy(() -> read(Map.of("mode", "PARALLEL", "children", "a")))
        .hasMessageContaining("children");
    assertThatThrownBy(() -> read(Map.of("mode", "PARALLEL", "children", List.of(1))))
        .hasMessageContaining("distinct");
    assertThatThrownBy(() -> read(Map.of("mode", "PARALLEL", "children", List.of("a", "a"))))
        .hasMessageContaining("distinct");
    assertThatThrownBy(() -> read(Map.of("children", List.of("a")))).hasMessageContaining("mode");
    assertThatThrownBy(
            () -> read(Map.of("mode", "QUORUM", "children", List.of("a", "b"), "quorum", "x")))
        .hasMessageContaining("number");
    assertThatThrownBy(
            () -> read(Map.of("mode", "PARALLEL", "children", List.of("a"), "window", 0)))
        .hasMessageContaining("window");
    assertThat(
            read(Map.of("mode", "QUORUM", "children", List.of("a", "b"), "window", 250)).window())
        .isEqualTo(Duration.ofMillis(250));
    assertThat(CompositeMode.PARALLEL.needsControlPlanes()).isFalse();
    assertThat(CompositeMode.CHAIN.needsControlPlanes()).isTrue();
    assertThat(CompositeMode.FIRST_MATCH.needsControlPlanes()).isTrue();
    assertThat(CompositeMode.QUORUM.needsControlPlanes()).isFalse();
  }

  private static CompositeSettings read(final Map<String, Object> map) {
    return CompositeSettings.read(new SupervisorProfile("p", "composite", map, false));
  }

  /** A factory of control planes and of plain supervisors. */
  private static final class Kinds {
    final SupervisorFactory plane =
        factory(
            "plane",
            profile ->
                new ControlPlanePlugin() {
                  @Override
                  public String id() {
                    return profile.id();
                  }

                  @Override
                  public Mono<ControlDecision> decide(final ExecutionEvent event) {
                    return Mono.empty();
                  }
                });
    final SupervisorFactory plain = factory("plain", profile -> new Plain(0, Mono.empty()));
    final SupervisorResolver resolver;
    final CompositeSupervisorFactory composite;

    Kinds() {
      final var beans = new StaticListableBeanFactory();
      beans.addBean("plane", plane);
      beans.addBean("plain", plain);
      resolver = new SupervisorResolver(beans.getBeanProvider(SupervisorFactory.class));
      final var holder = new StaticListableBeanFactory();
      holder.addBean("r", resolver);
      composite = new CompositeSupervisorFactory(holder.getBeanProvider(SupervisorResolver.class));
      beans.addBean("composite", composite);
    }
  }

  private static SupervisorFactory factory(
      final String type,
      final java.util.function.Function<SupervisorProfile, ExecutionSupervisor> make) {
    return new SupervisorFactory() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public ExecutionSupervisor create(final SupervisorProfile profile) {
        return make.apply(profile);
      }
    };
  }

  private static SupervisorProfile profile(
      final String id, final String type, final String mode, final String... children) {
    return new SupervisorProfile(
        id, type, Map.of("mode", mode, "children", new ArrayList<>(List.of(children))), false);
  }

  @Test
  void resolverMakesChildrenAndFindsNestedParents() {
    final Kinds kinds = new Kinds();
    final var leaf = new SupervisorProfile("leaf", "plane", Map.of(), false);
    final var mid = profile("mid", "composite", "CHAIN", "leaf");
    final var top = profile("top", "composite", "PARALLEL", "mid");
    final var odd = new SupervisorProfile("odd", "composite", Map.of("children", "x"), false);
    kinds.resolver.refresh(List.of(leaf, mid, top, odd));

    assertThat(kinds.resolver.resolve("leaf").id()).isEqualTo("leaf");
    assertThat(kinds.resolver.compositesUsing("leaf")).containsExactly("mid", "top");
    assertThat(kinds.resolver.compositesUsing("top")).isEmpty();
    assertThatThrownBy(() -> kinds.resolver.resolve("nope")).hasMessageContaining("Unknown child");
    kinds.resolver.refresh(List.of(new SupervisorProfile("alien", "alien", Map.of(), false)));
    assertThatThrownBy(() -> kinds.resolver.resolve("alien")).hasMessageContaining("unknown type");
  }

  @Test
  void factoryBuildsChainsOfControlPlanesAndRefusesPlainChildren() {
    final Kinds kinds = new Kinds();
    kinds.resolver.refresh(
        List.of(
            new SupervisorProfile("p1", "plane", Map.of(), false),
            new SupervisorProfile("s1", "plain", Map.of(), false)));

    final var chain = profile("c", "composite", "CHAIN", "p1");
    assertThat(((CompositeSupervisor) kinds.composite.create(chain)).childIds())
        .containsExactly("p1");
    assertThat(kinds.composite.type()).isEqualTo("composite");
    kinds.composite.validate(profile("c", "composite", "PARALLEL", "s1"));
    assertThatThrownBy(() -> kinds.composite.create(profile("c", "composite", "CHAIN", "s1")))
        .hasMessageContaining("needs control planes");
  }

  @Test
  void treeCheckWalksNestedCompositesWithoutFalseAlarms() {
    final Kinds kinds = new Kinds();
    final var inner = profile("inner", "composite", "PARALLEL", "p1");
    kinds.resolver.refresh(List.of(new SupervisorProfile("p1", "plane", Map.of(), false), inner));
    assertThat(kinds.composite.create(profile("outer", "composite", "PARALLEL", "inner")))
        .isNotNull();
  }

  @Test
  void moreSettingsLimitsAndStops() {
    assertThatThrownBy(() -> read(Map.of("mode", "PARALLEL", "children", List.of(" "))))
        .hasMessageContaining("distinct");
    assertThatThrownBy(
            () -> read(Map.of("mode", "QUORUM", "children", List.of("a", "b"), "quorum", 1)))
        .hasMessageContaining("quorum");
    assertThatThrownBy(
            () -> read(Map.of("mode", "PARALLEL", "children", List.of("a"), "window", 70_000)))
        .hasMessageContaining("window");
    final var settings =
        new CompositeSettings(
            CompositeMode.PARALLEL, List.of("a"), ReviewMode.ALL, 0, Duration.ofSeconds(1));
    final var stopNode = new ExecutionControlCommand.StopNodeCommand("e1", "n", false, null);
    StepVerifier.withVirtualTime(
            () ->
                new CompositeSupervisor(
                        "c",
                        settings,
                        List.of("a"),
                        List.of(new Plain(0, Mono.never())),
                        Clock.systemUTC())
                    .review(new ControlRequest(stopNode, "a", "s", "wf")))
        .thenAwait(Duration.ofSeconds(5))
        .expectNext(Decision.ALLOW)
        .verifyComplete();
  }

  @Test
  void firstMatchSendsAnyDecisionAndSkipsFailingPlane() {
    final ControlPlanePlugin failing =
        new ControlPlanePlugin() {
          @Override
          public String id() {
            return "bad";
          }

          @Override
          public Mono<ControlDecision> decide(final ExecutionEvent event) {
            return Mono.error(new IllegalStateException("boom"));
          }
        };
    final ControlPlanePlugin pausing =
        new ControlPlanePlugin() {
          @Override
          public String id() {
            return "good";
          }

          @Override
          public Mono<ControlDecision> decide(final ExecutionEvent event) {
            return Mono.just(new ControlDecision(List.of(PAUSE), 1, ControlDecision.Source.RULE));
          }
        };
    final var settings =
        new CompositeSettings(
            CompositeMode.FIRST_MATCH, List.of("a", "b"), ReviewMode.ALL, 0, Duration.ofSeconds(1));
    StepVerifier.create(
            new CompositeSupervisor(
                    "c",
                    settings,
                    settings.children(),
                    List.of(failing, pausing),
                    Clock.systemUTC())
                .supervise(Flux.just(EVENT)))
        .expectNext(PAUSE)
        .verifyComplete();
  }

  @Test
  void deepOrOddCompositesAreNotFollowedForever() {
    final Kinds kinds = new Kinds();
    final List<SupervisorProfile> all = new ArrayList<>();
    all.add(new SupervisorProfile("leaf", "plane", Map.of(), false));
    String below = "leaf";
    for (int i = 0; i < 6; i++) {
      all.add(profile("c" + i, "composite", "PARALLEL", below));
      below = "c" + i;
    }
    all.add(new SupervisorProfile("odd", "composite", Map.of("children", List.of(1)), false));
    kinds.resolver.refresh(all);
    assertThat(kinds.resolver.compositesUsing("leaf")).contains("c0", "c1", "c2");
    assertThat(kinds.resolver.compositesUsing("c5")).isEmpty();
  }
}
