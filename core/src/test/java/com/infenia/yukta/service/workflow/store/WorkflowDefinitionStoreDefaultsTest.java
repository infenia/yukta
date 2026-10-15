// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class WorkflowDefinitionStoreDefaultsTest {

  /** A store that keeps no history. */
  private static final WorkflowDefinitionStore PLAIN =
      new WorkflowDefinitionStore() {
        @Override
        public Mono<Void> save(final String sessionId, final WorkflowDefinition definition) {
          return Mono.empty();
        }

        @Override
        public Mono<WorkflowDefinition> find(final String sessionId, final String workflowId) {
          return Mono.empty();
        }

        @Override
        public Mono<Map<String, WorkflowDefinition>> findAll(final String sessionId) {
          return Mono.just(Map.of());
        }

        @Override
        public Mono<Void> remove(final String sessionId, final String workflowId) {
          return Mono.empty();
        }

        @Override
        public Mono<Void> removeAll(final String sessionId) {
          return Mono.empty();
        }
      };

  @Test
  void storeWithoutHistory_hasNoVersions() {
    assertThat(PLAIN.revisions("s", "w").collectList().block()).isEmpty();
    assertThat(PLAIN.findRevision("s", "w", "any").block()).isNull();
  }

  @Test
  void storeWithoutNotes_refusesToSaveOne() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> PLAIN.saveNote("s", "w", "v", "text").block())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
