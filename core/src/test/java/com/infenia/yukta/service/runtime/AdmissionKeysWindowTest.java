// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.model.control.OnLimit;
import com.infenia.yukta.model.workflow.WorkflowAdmission;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowKey;
import com.infenia.yukta.plugin.store.SecretProvider;
import com.infenia.yukta.plugin.util.VariableResolver;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
class AdmissionKeysWindowTest {

  private static final String CUSTOMER_KEY = "${payload.customer}";
  private static final String CUSTOMER_FIELD = "customer";

  private final WorkflowDefinitionStore store = mock(WorkflowDefinitionStore.class);
  private final AdmissionKeys keys =
      new AdmissionKeys(store, new VariableResolver(mock(SecretProvider.class)));

  private static WorkflowDefinition workflow(final WorkflowAdmission admission) {
    return new WorkflowDefinition("w", "d", List.of(), List.of(), List.of(), Map.of(), admission);
  }

  private static WorkflowDefinition keyedBy(final String key) {
    return workflow(
        new WorkflowAdmission(
            new WorkflowAdmission.Concurrency(key, 2, OnLimit.REJECT), null, null));
  }

  @Test
  void throttle_claimsItsKey_orTheWholeWorkflowWithoutOne() {
    final WorkflowDefinition keyed =
        workflow(
            new WorkflowAdmission(
                null, new WorkflowAdmission.Throttle(CUSTOMER_KEY, 3, 60_000), null));
    final WorkflowDefinition whole =
        workflow(new WorkflowAdmission(null, new WorkflowAdmission.Throttle(null, 3, 1_000), null));

    StepVerifier.create(keys.claim(keyed, Map.of(CUSTOMER_FIELD, "c1")))
        .assertNext(claims -> {
          assertThat(claims.concurrency()).isNull();
          assertThat(claims.throttle())
              .isEqualTo(new ExecutionScheduler.ThrottleClaim("c1", 3, Duration.ofMinutes(1)));
        })
        .verifyComplete();
    StepVerifier.create(keys.claim(whole, Map.of()))
        .assertNext(claims -> assertThat(claims.throttle().key()).isEmpty())
        .verifyComplete();
  }

  @Test
  void debounce_resolvesItsKey_andIsEmptyWithoutOne() {
    final WorkflowDefinition debounced =
        workflow(
            new WorkflowAdmission(null, null, new WorkflowAdmission.Debounce(CUSTOMER_KEY, 5_000)));

    StepVerifier.create(keys.debounce(debounced, Map.of(CUSTOMER_FIELD, "c1")))
        .assertNext(debounce -> {
          assertThat(debounce.key()).isEqualTo("c1");
          assertThat(debounce.period()).isEqualTo(Duration.ofSeconds(5));
        })
        .verifyComplete();
    StepVerifier.create(keys.debounce(workflow(null), Map.of())).verifyComplete();
    StepVerifier.create(keys.debounce(keyedBy(CUSTOMER_KEY), Map.of())).verifyComplete();
  }

  @Test
  void debounceWindow_isOpenedOnce_andClosedOnlyByTheRunThatOpenedIt() {
    final WorkflowKey workflow = new WorkflowKey("s", "w");
    final AdmissionKeys.Debounce debounce = new AdmissionKeys.Debounce("c1", Duration.ofMinutes(1));

    assertThat(keys.joinOrOpen(workflow, debounce, "e1")).isEqualTo("e1");
    assertThat(keys.joinOrOpen(workflow, debounce, "e2")).isEqualTo("e1");
    keys.close(workflow, debounce, "e2");
    assertThat(keys.joinOrOpen(workflow, debounce, "e3")).isEqualTo("e1");
    keys.close(workflow, debounce, "e1");
    assertThat(keys.joinOrOpen(workflow, debounce, "e4")).isEqualTo("e4");
  }

  @Test
  void blankOrMissingKey_meansTheWholeWorkflow() {
    final WorkflowDefinition blankThrottle =
        workflow(new WorkflowAdmission(null, new WorkflowAdmission.Throttle("  ", 3, 1_000), null));
    final WorkflowDefinition wholeDebounce =
        workflow(new WorkflowAdmission(null, null, new WorkflowAdmission.Debounce(null, 1_000)));

    StepVerifier.create(keys.claim(blankThrottle, Map.of()))
        .assertNext(claims -> assertThat(claims.throttle().key()).isEmpty())
        .verifyComplete();
    StepVerifier.create(keys.debounce(wholeDebounce, Map.of()))
        .assertNext(debounce -> assertThat(debounce.key()).isEmpty())
        .verifyComplete();
  }

  @Test
  void closingWindowThatIsNotOpen_leavesTheNextStartToOpenIt() {
    final WorkflowKey workflow = new WorkflowKey("s", "w");
    final AdmissionKeys.Debounce debounce = new AdmissionKeys.Debounce("c2", Duration.ofMinutes(1));

    keys.close(workflow, debounce, "e1");
    assertThat(keys.joinOrOpen(workflow, debounce, "e2")).isEqualTo("e2");
  }
}
