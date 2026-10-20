// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every {@link WorkflowDefinitionStore} must do. Extend it in the test of an implementation
 * and return a store that starts empty from {@link #create()}.
 */
public abstract class WorkflowDefinitionStoreContract {

  /** Creates the contract. */
  protected WorkflowDefinitionStoreContract() {
    super();
  }

  /**
   * A new, empty store.
   *
   * @return the store under test
   */
  protected abstract WorkflowDefinitionStore create();

  private static WorkflowDefinition definition(final String workflowId, final String description) {
    return new WorkflowDefinition(
        workflowId, description, List.of(new Node("n", "CONSTANT_SOURCE", Map.of())), List.of());
  }

  @Test
  void savedDefinitionsAreFoundBySessionAndWorkflow() {
    final WorkflowDefinitionStore store = create();
    final WorkflowDefinition first = definition("w1", "one");
    final WorkflowDefinition second = definition("w2", "two");

    store.save("s", first).block();
    store.save("s", second).block();

    assertThat(store.find("s", "w1").block()).isEqualTo(first);
    assertThat(store.find("other", "w1").block()).isNull();
    assertThat(store.findAll("s").block()).containsOnlyKeys("w1", "w2");
    assertThat(store.findAll("none").block()).isEmpty();
  }

  @Test
  void savingAgainReplacesTheDefinition() {
    final WorkflowDefinitionStore store = create();
    store.save("s", definition("w", "old")).block();

    final WorkflowDefinition updated = definition("w", "new");
    store.save("s", updated).block();

    assertThat(store.find("s", "w").block()).isEqualTo(updated);
  }

  @Test
  void removeTakesOutOneWorkflow_removeAllTakesOutTheSession() {
    final WorkflowDefinitionStore store = create();
    store.save("s", definition("w1", "one")).block();
    store.save("s", definition("w2", "two")).block();

    store.remove("s", "w1").block();
    assertThat(store.findAll("s").block()).containsOnlyKeys("w2");

    store.removeAll("s").block();
    assertThat(store.findAll("s").block()).isEmpty();
  }
}
