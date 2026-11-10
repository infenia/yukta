// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.review.ControlReviewer;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.TooManyStaticImports")
class OwnershipMapTest {

  private static final String TERMINAL = "CONSOLE_TERMINAL";

  private final ControlReviewer reviewer = mock(ControlReviewer.class);
  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final OwnershipMap map = new OwnershipMap(reviewer, definitions);

  @Test
  void theRunLevelAndEachNodeNameTheirOwner() {
    when(definitions.find("s", "w"))
        .thenReturn(
            Mono.just(
                Fixtures.workflow(
                    "w",
                    "d",
                    List.of(
                        Fixtures.node("build.a", TERMINAL, Map.of()),
                        Fixtures.node("deploy", TERMINAL, Map.of())),
                    List.of())));
    when(reviewer.defaultSupervisorEnabled()).thenReturn(true);
    when(reviewer.capability(
            eq("s"), eq("w"), eq("build.a"), any(ExecutionControlCommand.PauseNodeCommand.class)))
        .thenReturn(new ControlReviewer.Capability(true, "guard", null));
    when(reviewer.capability(
            eq("s"), eq("w"), eq("deploy"), any(ExecutionControlCommand.PauseNodeCommand.class)))
        .thenReturn(new ControlReviewer.Capability(true, "default", null));
    when(reviewer.capability(
            eq("s"), eq("w"), eq(null), any(ExecutionControlCommand.PauseWorkflowCommand.class)))
        .thenReturn(new ControlReviewer.Capability(true, "run-owner", null));

    StepVerifier.create(map.ownersOf("s", "w"))
        .assertNext(
            owned -> {
              assertThat(owned.run()).isEqualTo("run-owner");
              assertThat(owned.nodes())
                  .containsExactly(Map.entry("build.a", "guard"), Map.entry("deploy", "default"));
              assertThat(owned.defaultSupervisor()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void anUnknownWorkflowIsNotFound() {
    when(definitions.find("s", "nope")).thenReturn(Mono.empty());
    StepVerifier.create(map.ownersOf("s", "nope")).expectError(NotFoundException.class).verify();
  }

  @Test
  void unownedListsTheWorkflowsWhoseRunAndNodesAllFallToTheDefault() {
    final var free =
        Fixtures.workflow("free", "d", List.of(Fixtures.node("a", TERMINAL, Map.of())), List.of());
    final var partly =
        Fixtures.workflow(
            "partly", "d", List.of(Fixtures.node("b", TERMINAL, Map.of())), List.of());
    final var run =
        Fixtures.workflow("run", "d", List.of(Fixtures.node("c", TERMINAL, Map.of())), List.of());
    when(definitions.findAll("s"))
        .thenReturn(Mono.just(Map.of("free", free, "partly", partly, "run", run)));
    when(definitions.findAll("t")).thenReturn(Mono.just(Map.of()));
    when(reviewer.capability(eq("s"), any(), any(), any()))
        .thenAnswer(
            call -> {
              final String workflow = call.getArgument(1);
              final String node = call.getArgument(2);
              final boolean owned =
                  "partly".equals(workflow) && "b".equals(node)
                      || "run".equals(workflow) && node == null;
              return new ControlReviewer.Capability(true, owned ? "guard" : "default", null);
            });

    StepVerifier.create(map.unowned(Flux.just("s", "t")))
        .expectNext(new OwnershipMap.WorkflowRef("s", "free"))
        .verifyComplete();
  }
}
