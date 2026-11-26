// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.control.OnLimit;
import com.infenia.yukta.model.workflow.WorkflowAdmission;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.store.SecretProvider;
import com.infenia.yukta.plugin.util.VariableResolver;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
class AdmissionKeysTest {

  private static final String CUSTOMER_KEY = "${payload.customer}";

  private final WorkflowDefinitionStore store = mock(WorkflowDefinitionStore.class);
  private final AdmissionKeys keys =
      new AdmissionKeys(store, new VariableResolver(mock(SecretProvider.class)));

  private static WorkflowDefinition workflow(final WorkflowAdmission admission) {
    return new WorkflowDefinition("w", "d", List.of(), List.of(), List.of(), Map.of(), admission);
  }

  private static WorkflowDefinition keyedBy(final String key) {
    return workflow(
        new WorkflowAdmission(new WorkflowAdmission.Concurrency(key, 2, OnLimit.REJECT)));
  }

  @Test
  void workflowWithoutKeyLimitClaimsNothing() {
    StepVerifier.create(keys.claim(workflow(null), Map.of())).verifyComplete();
    StepVerifier.create(keys.claim(workflow(new WorkflowAdmission(null)), Map.of()))
        .verifyComplete();
  }

  @Test
  void keyResolvesFromThePayload_withTheLimitAndOverflow() {
    StepVerifier.create(keys.claim(keyedBy(CUSTOMER_KEY), Map.of("customer", 42)))
        .assertNext(claim -> {
          assertThat(claim.key()).isEqualTo("42");
          assertThat(claim.limit()).isEqualTo(2);
          assertThat(claim.overflow()).isEqualTo(OnLimit.REJECT);
        })
        .verifyComplete();
    StepVerifier.create(keys.claim(keyedBy("deploy-${payload.env}"), Map.of("env", "prod")))
        .assertNext(claim -> assertThat(claim.key()).isEqualTo("deploy-prod"))
        .verifyComplete();
  }

  @Test
  void keyThatResolvesToNothing_refusesTheStart() {
    StepVerifier.create(keys.claim(keyedBy(CUSTOMER_KEY), Map.of()))
        .expectErrorSatisfies(error ->
            assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CUSTOMER_KEY))
        .verify();
    StepVerifier.create(keys.claim(keyedBy(CUSTOMER_KEY), Map.of("customer", " ")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void restartOrResume_readsTheStoredDefinition_andClaimsNothingWhenItIsGone() {
    when(store.find("s", "w")).thenReturn(Mono.just(keyedBy(CUSTOMER_KEY)));
    when(store.find("s", "gone")).thenReturn(Mono.empty());

    StepVerifier.create(keys.claim("s", "w", Map.of("customer", "c1")))
        .assertNext(claim -> assertThat(claim.key()).isEqualTo("c1"))
        .verifyComplete();
    StepVerifier.create(keys.claim("s", "gone", Map.of())).verifyComplete();
  }

  @Test
  void overflowDefaultsToQueue() {
    assertThat(new WorkflowAdmission.Concurrency("k", 1, null).overflow()).isEqualTo(OnLimit.QUEUE);
  }
}
