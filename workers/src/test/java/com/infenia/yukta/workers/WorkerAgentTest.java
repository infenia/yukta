// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.service.worker.WorkerRegistration;
import java.time.Duration;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.rsocket.RSocketRequester;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class WorkerAgentTest {

  @Test
  void stopBeforeStart_isHarmless() {
    final WorkerAgent agent =
        new WorkerAgent(
            reactor.core.publisher.Mono.just(mock(RSocketRequester.class)),
            "secret",
            new WorkerRegistration("w", Set.of(), Set.of(), 1),
            Duration.ofSeconds(1),
            () -> 0);

    assertThatCode(agent::stop).doesNotThrowAnyException();
  }
}
