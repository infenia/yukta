// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.ConsultAware;
import com.infenia.yukta.plugin.control.engine.Consultant;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineBackedSupervisor;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.review.BoundSupervisor;
import com.infenia.yukta.service.supervisor.hierarchy.SupervisorResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** A supervisor's engine asks its parents a question; the service finds the parents' engines. */
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.CloseResource"})
class SupervisorServiceConsultTest {

  private static final String TYPE = "ask";

  private final SupervisorHost host = mock(SupervisorHost.class);
  private final AtomicReference<Consultant> asking = new AtomicReference<>();

  /** Makes a supervisor that keeps the way to ask its parents. */
  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public String type() {
          return TYPE;
        }

        @Override
        public List<ConfigField> getConfigSchema() {
          return List.of();
        }

        @Override
        public ExecutionSupervisor create(final Supervisor supervisor) {
          return new Asking(asking);
        }
      };

  private final SupervisorResolver resolver = new SupervisorResolver();
  private final SupervisorService service =
      new SupervisorService(
          new InMemorySupervisorStore(), List.of(factory), host, new InMemoryExecutionLogStore());

  /** An observer that is told how to ask its parents. */
  private record Asking(AtomicReference<Consultant> asking)
      implements ExecutionSupervisor, ConsultAware {

    @Override
    public String id() {
      return "asking";
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }

    @Override
    public void consultWith(final Consultant consultant) {
      asking.set(consultant);
    }
  }

  private Consultant started(final Supervisor child) {
    service.setResolver(resolver);
    for (final String parent : child.parents()) {
      StepVerifier.create(service.saveSupervisor(shell(parent)))
          .expectNextCount(1)
          .verifyComplete();
    }
    StepVerifier.create(service.saveSupervisor(child)).expectNextCount(1).verifyComplete();
    resolver.refresh(service.supervisors().collectList().block());
    final SupervisorAttachment attachment =
        new SupervisorAttachment("k1", child.id(), new SupervisorScope.Session("A"), true);
    StepVerifier.create(service.attach(attachment)).expectNext(attachment).verifyComplete();
    final ArgumentCaptor<BoundSupervisor> bound = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(bound.capture());
    assertThat(bound.getValue().delegate()).isInstanceOf(Asking.class);
    return asking.get();
  }

  private static Supervisor shell(final String id, final String... parents) {
    return new Supervisor(id, TYPE, Map.of(), false, null, List.of(parents), Map.of());
  }

  private static BoundSupervisor running(final String parent, final ExecutionSupervisor made) {
    return new BoundSupervisor(
        new SupervisorAttachment("p-" + parent, parent, new SupervisorScope.Session("A"), true),
        made,
        false,
        SupervisorCapabilities.from(new Supervisor(parent, TYPE, Map.of(), false)),
        false);
  }

  @Test
  void theRunningEngineOfParentAnswersTheQuestion() {
    final var boss =
        new EngineBackedSupervisor(
            "boss",
            new SupervisorScope.Session("A"),
            signals ->
                signals
                    .ofType(Signal.Delegated.class)
                    .map(d -> new Directive.DelegationAnswer(d.id(), Map.of("go", true))),
            true);
    final var running = boss.directives(Flux.never()).subscribe();
    when(host.attachedOf("boss")).thenReturn(List.of(running("boss", boss)));
    final Consultant consultant = started(shell("kid", "boss"));
    try {
      StepVerifier.create(consultant.consult(DelegationKind.CONSULT, Map.of("q", "retry?")))
          .expectNext(Map.of("boss", Map.of("go", true)))
          .verifyComplete();
    } finally {
      running.dispose();
    }
  }

  @Test
  void parentThatIsNotRunningOrNotAnEngineIsSilent() {
    when(host.attachedOf(any()))
        .thenReturn(List.of())
        .thenReturn(List.of(running("plain", new Asking(new AtomicReference<>()))));
    final Consultant consultant = started(shell("kid", "off", "plain"));
    StepVerifier.create(consultant.consult(DelegationKind.CONSULT, Map.of()))
        .expectNext(Map.of())
        .verifyComplete();
  }

  @Test
  void supervisorWithoutParentsGetsNoAnswerWithoutAsking() {
    final Consultant consultant = started(shell("kid"));
    StepVerifier.create(consultant.consult(DelegationKind.CONSULT, Map.of()))
        .expectNext(Map.of())
        .expectComplete()
        .verify(Duration.ofSeconds(2));
  }

  @Test
  void withoutResolverNobodyIsAsked() {
    final SupervisorService bare =
        new SupervisorService(
            new InMemorySupervisorStore(), List.of(factory), host, new InMemoryExecutionLogStore());
    StepVerifier.create(bare.saveSupervisor(shell("kid"))).expectNextCount(1).verifyComplete();
    final SupervisorAttachment attachment =
        new SupervisorAttachment("k2", "kid", new SupervisorScope.Session("A"), true);
    StepVerifier.create(bare.attach(attachment)).expectNext(attachment).verifyComplete();
    StepVerifier.create(asking.get().consult(DelegationKind.CONSULT, Map.of()))
        .expectNext(Map.of())
        .verifyComplete();
  }
}
