// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/events/stream}: the server-sent events of all runs. They are not replayed and the
 * response starts with the first event, so the test opens the stream first and starts runs until an
 * event comes.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class EventsE2eTest {

  @Test
  void runStatusChangesAreStreamed(final Api api, final Scenarios scenarios) {
    final String session = Scenarios.unique("events");
    scenarios.applySession(SupervisionE2eTest.session(session));
    final CompletableFuture<String> stream =
        CompletableFuture.supplyAsync(() ->
            Api.events(
                api.client().get().uri("/api/events/stream?type=WorkflowStatusChanged"),
                Duration.ofSeconds(3)));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(300))
        .until(() -> {
          scenarios.startWorkflow(session, "w", Map.of());
          return stream.isDone();
        });

    assertThat(stream.join()).contains("WorkflowStatusChanged").contains("severity");
  }

  @Test
  void anUnknownTypeIs400(final Api api) {
    api.failure(api.client().get().uri("/api/events/stream?type=bogus").exchange(), 400);
  }
}
