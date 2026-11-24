// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.sessionstore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.SessionSnapshot;
import com.infenia.yukta.model.session.SessionSummary;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The default {@link SessionConfigStore#summaries()}, for stores that do not override it. */
@NoArgsConstructor
class SessionConfigStoreSummariesTest {

  private static SessionSnapshot snapshot(final String id) {
    return new SessionSnapshot(id, "n-" + id, "", "", "", Map.of(), "", Map.of());
  }

  @Test
  void summariesAreSortedById_andSessionThatCannotBeReadIsLeftOut() {
    final SessionConfigStore store =
        mock(
            SessionConfigStore.class,
            Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
    when(store.getSessionIds()).thenReturn(Flux.just("c", "bad", "a"));
    when(store.getAllConfigs("a")).thenReturn(Mono.just(snapshot("a")));
    when(store.getAllConfigs("c")).thenReturn(Mono.just(snapshot("c")));
    when(store.getAllConfigs("bad")).thenReturn(Mono.error(new IllegalStateException("io")));

    StepVerifier.create(store.summaries().map(SessionSummary::sessionId))
        .expectNext("a", "c")
        .verifyComplete();
  }
}
