// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.workflow.store.DefinitionRevision;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class R2dbcWorkflowDefinitionStoreTest extends PostgresTestSupport {

  private R2dbcWorkflowDefinitionStore store;

  private static WorkflowDefinition definition(final String workflowId, final String description) {
    return new WorkflowDefinition(
        workflowId,
        description,
        List.of(new WorkflowDefinition.Node("n1", "CONSTANT_SOURCE", Map.of("k", "v"))),
        List.of());
  }

  @BeforeEach
  void setUp() {
    store = new R2dbcWorkflowDefinitionStore(db, new ObjectMapper());
  }

  @Test
  void saveFindListAndRemove() {
    store.save("s", definition("w1", "one")).block();
    store.save("s", definition("w2", "two")).block();
    store.save("other", definition("w1", "elsewhere")).block();

    assertThat(store.find("s", "w1").block()).isEqualTo(definition("w1", "one"));
    assertThat(store.find("s", "nope").block()).isNull();
    assertThat(store.findAll("s").block()).containsOnlyKeys("w1", "w2");
    assertThat(store.findAll("nobody").block()).isEmpty();

    store.save("s", definition("w1", "changed")).block();
    assertThat(store.find("s", "w1").block().description()).isEqualTo("changed");

    store.remove("s", "w1").block();
    assertThat(store.findAll("s").block()).containsOnlyKeys("w2");
    assertThat(store.revisions("s", "w1").collectList().block()).isEmpty();
    store.removeAll("s").block();
    assertThat(store.findAll("s").block()).isEmpty();
    assertThat(store.find("other", "w1").block()).isNotNull();
  }

  @Test
  void versionsAreListedNewestFirst_andRestorable_butIdenticalSavesAddNone()
      throws InterruptedException {
    store.save("s", definition("w", "one")).block();
    store.save("s", definition("w", "one")).block();
    Thread.sleep(2);
    store.save("s", definition("w", "two")).block();

    final List<DefinitionRevision> revisions = store.revisions("s", "w").collectList().block();

    assertThat(revisions).hasSize(2);
    assertThat(revisions.get(0).savedAt()).isAfterOrEqualTo(revisions.get(1).savedAt());
    assertThat(store.findRevision("s", "w", revisions.get(0).id()).block())
        .isEqualTo(definition("w", "two"));
    assertThat(store.findRevision("s", "w", revisions.get(1).id()).block())
        .isEqualTo(definition("w", "one"));
    assertThat(store.findRevision("s", "w", "nope").block()).isNull();
  }

  @Test
  void onlyTheNewestVersionsAreKept() throws InterruptedException {
    for (int i = 0; i < R2dbcWorkflowDefinitionStore.MAX_REVISIONS + 3; i++) {
      store.save("s", definition("w", "v" + i)).block();
      Thread.sleep(1);
    }

    final List<DefinitionRevision> revisions = store.revisions("s", "w").collectList().block();

    assertThat(revisions).hasSize(R2dbcWorkflowDefinitionStore.MAX_REVISIONS);
    assertThat(store.findRevision("s", "w", revisions.get(0).id()).block().description())
        .isEqualTo("v" + (R2dbcWorkflowDefinitionStore.MAX_REVISIONS + 2));
  }
}
