// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.ConsultAware;
import com.infenia.yukta.plugin.control.engine.Consultant;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.ReadsAware;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.review.BoundSupervisor;
import com.infenia.yukta.service.supervisor.engine.SupervisorQueries;
import com.infenia.yukta.service.supervisor.ownership.NodeGroupIndex;
import com.infenia.yukta.service.workflow.store.InMemoryWorkflowDefinitionStore;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * What the service keeps up after a start: groups that move, ownership shapes, reads, retired
 * types.
 */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.TooManyStaticImports"
})
class SupervisorServiceUpkeepTest {

  private static final String TYPE = "t";

  private final SupervisorHost host = mock(SupervisorHost.class);
  private final InMemorySupervisorStore store = new InMemorySupervisorStore();
  private final AtomicReference<Consultant> asking = new AtomicReference<>();
  private final AtomicReference<EngineReads> reading = new AtomicReference<>();

  /** An observer that wants reads and a way to ask its parents. */
  private final class Observer implements ExecutionSupervisor, ReadsAware, ConsultAware {

    @Override
    @SuppressWarnings("PMD.ShortMethodName")
    public String id() {
      return "observer";
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }

    @Override
    public void readsFrom(final EngineReads reads) {
      reading.set(reads);
    }

    @Override
    public void consultWith(final Consultant consultant) {
      asking.set(consultant);
    }
  }

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
          return new Observer();
        }
      };

  private final SupervisorService service =
      new SupervisorService(store, List.of(factory), host, new InMemoryExecutionLogStore());

  private void saved(final String id) {
    StepVerifier.create(service.saveSupervisor(new Supervisor(id, TYPE, Map.of(), false)))
        .expectNextCount(1)
        .verifyComplete();
  }

  private static com.infenia.yukta.model.workflow.WorkflowDefinition workflow(
      final String... nodes) {
    return Fixtures.workflow(
        "w",
        "d",
        java.util.Arrays.stream(nodes).map(n -> Fixtures.node(n, "T", Map.of())).toList(),
        List.of(),
        List.of());
  }

  @Test
  void attachmentWhoseGroupMovedIsStartedAgainWithTheNewNodes() {
    saved("a");
    final NodeGroupIndex groups = new NodeGroupIndex();
    groups.put("s", Map.of("w", workflow("build.compile", "deploy")));
    service.setNodeGroups(groups);
    final SupervisorAttachment section =
        new SupervisorAttachment(
            "a1", "a", new SupervisorScope.Section("w", "s", Set.of("group:build")), true);
    StepVerifier.create(service.attach(section)).expectNextCount(1).verifyComplete();
    final ArgumentCaptor<BoundSupervisor> first = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(first.capture());
    when(host.attached("a1")).thenReturn(Optional.of(first.getValue()));

    // the definition now has two nodes in the group: the running one owns too few
    groups.put("s", Map.of("w", workflow("build.compile", "build.test", "deploy")));
    StepVerifier.create(service.regroup("s")).verifyComplete();

    final ArgumentCaptor<BoundSupervisor> all = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host, atLeast(2)).attach(all.capture());
    assertThat(all.getValue().scope().matches("s", "w", "build.test")).isTrue();
  }

  @Test
  void regroupLeavesAloneWhatDidNotMoveAndOtherSessionsAndRunsWithoutGroups() {
    saved("a");
    StepVerifier.create(service.regroup("s")).verifyComplete(); // no group index at all
    final NodeGroupIndex groups = new NodeGroupIndex();
    groups.put("s", Map.of("w", workflow("build.compile")));
    service.setNodeGroups(groups);
    final SupervisorAttachment section =
        new SupervisorAttachment(
            "a1", "a", new SupervisorScope.Section("w", "s", Set.of("group:build")), true);
    StepVerifier.create(service.attach(section)).expectNextCount(1).verifyComplete();
    final ArgumentCaptor<BoundSupervisor> first = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(first.capture());
    when(host.attached("a1")).thenReturn(Optional.of(first.getValue()));

    StepVerifier.create(service.regroup("s")).verifyComplete(); // nothing moved
    StepVerifier.create(service.regroup("other")).verifyComplete(); // another session
    verify(host, never()).detach("a1");
  }

  @Test
  void supervisorThatCannotRestartAfterItsGroupMovedIsLoggedAndTheRestGoOn() {
    saved("a");
    final NodeGroupIndex groups = new NodeGroupIndex();
    groups.put("s", Map.of("w", workflow("build.compile")));
    service.setNodeGroups(groups);
    final SupervisorAttachment section =
        new SupervisorAttachment(
            "a1", "a", new SupervisorScope.Section("w", "s", Set.of("group:build")), true);
    StepVerifier.create(service.attach(section)).expectNextCount(1).verifyComplete();
    final ArgumentCaptor<BoundSupervisor> first = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(first.capture());
    when(host.attached("a1")).thenReturn(Optional.of(first.getValue()));
    org.mockito.Mockito.doThrow(new IllegalStateException("host down")).when(host).attach(any());
    groups.put("s", Map.of("w", workflow("build.compile", "build.test")));

    StepVerifier.create(service.regroup("s")).verifyComplete();
  }

  @Test
  void ownershipIsComparedAgainstTheNodesOfTheStoredDefinitions() {
    final InMemoryWorkflowDefinitionStore definitions = new InMemoryWorkflowDefinitionStore();
    definitions.save("s", workflow("build.compile", "deploy")).block();
    final List<SupervisorScope> scopes =
        List.of(
            new SupervisorScope.Workflow("w", "s"),
            new SupervisorScope.Session("s"),
            new SupervisorScope.Server());

    // without a store of definitions every workflow is unknown
    final var unknown = service.validatorFor(scopes).block();
    assertThat(unknown).isNotNull();
    assertThat(
            unknown.conflicts(
                new com.infenia.yukta.service.supervisor.ownership.OwnershipValidator.Claim(
                    "x", new SupervisorScope.Section("w", "s", Set.of("build.*"))),
                List.of(
                    new com.infenia.yukta.service.supervisor.ownership.OwnershipValidator.Claim(
                        "y", new SupervisorScope.Section("w", "s", Set.of("build.compile"))))))
        .hasSize(1);

    service.setDefinitions(definitions);
    final var validator = service.validatorFor(scopes).block();
    assertThat(validator).isNotNull();
    final var conflicts =
        validator.conflicts(
            new com.infenia.yukta.service.supervisor.ownership.OwnershipValidator.Claim(
                "x", new SupervisorScope.Section("w", "s", Set.of("build.*"))),
            List.of(
                new com.infenia.yukta.service.supervisor.ownership.OwnershipValidator.Claim(
                    "y", new SupervisorScope.Section("w", "s", Set.of("build.compile")))));
    assertThat(conflicts).hasSize(1);
  }

  @Test
  void supervisorThatReadsGetsTheReadsOfWhatItOwnsOnStart() {
    saved("a");
    final SupervisorQueries queries = mock(SupervisorQueries.class);
    when(queries.readsFor(any(), any())).thenReturn(EngineReads.NONE);
    @SuppressWarnings("unchecked")
    final ObjectProvider<SupervisorQueries> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(queries);
    service.setQueries(provider);

    final SupervisorAttachment owned =
        new SupervisorAttachment("a1", "a", new SupervisorScope.Session("s"), true);
    StepVerifier.create(service.attach(owned)).expectNextCount(1).verifyComplete();

    assertThat(reading.get()).isSameAs(EngineReads.NONE);
    final ArgumentCaptor<java.util.function.Supplier<List<String>>> held = ArgumentCaptor.captor();
    verify(queries).readsFor(any(), held.capture());
    when(host.held("a")).thenReturn(List.of("run1"));
    assertThat(held.getValue().get()).containsExactly("run1");
  }

  @Test
  void regroupLooksOnlyAtEnabledSectionsWithGroupsOfThatSession() {
    saved("a");
    final NodeGroupIndex groups = new NodeGroupIndex();
    groups.put("s", Map.of("w", workflow("build.compile")));
    service.setNodeGroups(groups);
    for (final SupervisorAttachment other :
        List.of(
            new SupervisorAttachment(
                "off", "a", new SupervisorScope.Section("w", "s", Set.of("group:build")), false),
            new SupervisorAttachment("whole", "a", new SupervisorScope.Workflow("w", "s"), true),
            new SupervisorAttachment(
                "plain", "a", new SupervisorScope.Section("w", "s", Set.of("deploy")), true),
            new SupervisorAttachment(
                "elsewhere",
                "a",
                new SupervisorScope.Section("w", "t", Set.of("group:build")),
                true))) {
      store.saveAttachment(other).block();
    }

    StepVerifier.create(service.regroup("s")).verifyComplete();

    verify(host, never()).attach(any());
  }
}
