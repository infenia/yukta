// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.workflow.store.DefinitionRevision;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class WorkflowVersionProviderTest {

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private final WorkflowVersionProvider provider =
      new WorkflowVersionProvider(definitions, controlBus);

  private static WorkflowDefinition definition(final String... nodeIds) {
    return new WorkflowDefinition(
        "w1",
        "d",
        java.util.Arrays.stream(nodeIds)
            .map(id -> new WorkflowDefinition.Node(id, "T", Map.of()))
            .toList(),
        List.of());
  }

  @Test
  void versions_listsTheRevisions() {
    final DefinitionRevision revision = new DefinitionRevision("0001-a", Instant.EPOCH);
    when(definitions.revisions("s", "w1")).thenReturn(Flux.just(revision));

    StepVerifier.create(provider.versions("s", "w1"))
        .assertNext(result -> assertThat(result.versions()).containsExactly(revision))
        .verifyComplete();
  }

  @Test
  void diff_comparesWithTheCurrentDefinitionWhenToIsOmitted() {
    when(definitions.findRevision("s", "w1", "0001-a")).thenReturn(Mono.just(definition("a")));
    when(definitions.find("s", "w1")).thenReturn(Mono.just(definition("a", "b")));
    when(definitions.findRevision("s", "w1", "0002-b")).thenReturn(Mono.just(definition("a", "c")));

    StepVerifier.create(provider.diff("s", "w1", "0001-a", null))
        .assertNext(diff -> assertThat(diff.addedNodes()).containsExactly("b"))
        .verifyComplete();
    StepVerifier.create(provider.diff("s", "w1", "0001-a", "0002-b"))
        .assertNext(diff -> assertThat(diff.addedNodes()).containsExactly("c"))
        .verifyComplete();
  }

  @Test
  void diff_needsFrom_andAnUnknownVersionIsNotFound() {
    StepVerifier.create(provider.diff("s", "w1", " ", null))
        .verifyError(IllegalArgumentException.class);
    when(definitions.findRevision("s", "w1", "nope")).thenReturn(Mono.empty());
    when(definitions.find("s", "w1")).thenReturn(Mono.just(definition("a")));

    StepVerifier.create(provider.diff("s", "w1", "nope", null)).verifyError(NotFoundException.class);
  }

  @Test
  void rollback_savesTheOldVersionAndPreparesIt() {
    final WorkflowDefinition old = definition("a");
    when(definitions.findRevision("s", "w1", "0001-a")).thenReturn(Mono.just(old));
    when(definitions.save("s", old)).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow("s", old)).thenReturn(Mono.empty());

    StepVerifier.create(provider.rollback("s", "w1", "0001-a"))
        .assertNext(result -> assertThat(result.version()).isEqualTo("0001-a"))
        .verifyComplete();
    verify(definitions).save("s", old);
  }

  @Test
  void rollback_needsAKnownVersion() {
    when(definitions.findRevision("s", "w1", "nope")).thenReturn(Mono.empty());

    StepVerifier.create(provider.rollback("s", "w1", " "))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(provider.rollback("s", "w1", "nope")).verifyError(NotFoundException.class);
  }
}
