// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.session.SessionService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SupervisionSummaryTest {

  private final OwnershipMap ownership = mock(OwnershipMap.class);
  private final SessionService sessions = mock(SessionService.class);
  private final SupervisionSummary summary = new SupervisionSummary(ownership, sessions);

  @Test
  void countsUnownedWorkflowsOfVisibleSessionsButNamesAtMostOneHundred() {
    final Flux<String> ids = Flux.just("s");
    when(sessions.getSessionIds(false)).thenReturn(ids);
    when(ownership.unowned(ids))
        .thenReturn(Flux.range(0, 101).map(i -> new OwnershipMap.WorkflowRef("s", "w" + i)));

    StepVerifier.create(summary.summarize())
        .assertNext(
            found -> {
              assertThat(found.unowned()).isEqualTo(101);
              assertThat(found.unownedWorkflows()).hasSize(SupervisionSummary.MAX_NAMED);
            })
        .verifyComplete();
  }
}
