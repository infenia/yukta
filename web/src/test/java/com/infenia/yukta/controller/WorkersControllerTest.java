// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.worker.WorkerInfo;
import com.infenia.yukta.service.worker.WorkerRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for {@link WorkersController}. */
@NoArgsConstructor
class WorkersControllerTest {

  @Test
  void list_wrapsTheLiveWorkers() {
    final WorkerRegistry registry = mock(WorkerRegistry.class);
    final WorkerInfo info =
        new WorkerInfo(
            "w1", Set.of("default"), Set.of("HTTP_REQUEST"), 4, 1, Instant.EPOCH, Instant.EPOCH);
    when(registry.list()).thenReturn(List.of(info));

    StepVerifier.create(new WorkersController(registry).list())
        .assertNext(
            response -> {
              assertThat(response.status()).isEqualTo(200);
              assertThat(response.data()).containsExactly(info);
            })
        .verifyComplete();
  }
}
