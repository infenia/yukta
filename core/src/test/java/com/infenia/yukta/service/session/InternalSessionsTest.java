// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.preparator.WorkflowPreparator;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class InternalSessionsTest {

  private final SessionConfigStore store = mock(SessionConfigStore.class);

  private final InternalSessions internal = new InternalSessions(store);

  private void tags(final String session, final Map<String, String> tags) {
    when(store.getTags(session)).thenReturn(Mono.just(tags));
  }

  @Test
  void sessionIsInternalWhenItsTagSaysTrue() {
    tags("sys", Map.of(InternalSessions.TAG, "true"));
    tags("plain", Map.of("team", "a"));
    when(store.getTags("empty")).thenReturn(Mono.empty());

    StepVerifier.create(internal.checkInternal("sys")).expectNext(true).verifyComplete();
    StepVerifier.create(internal.checkInternal("plain")).expectNext(false).verifyComplete();
    StepVerifier.create(internal.checkInternal("empty")).expectNext(false).verifyComplete();
    StepVerifier.create(internal.checkInternal(null)).expectNext(false).verifyComplete();
  }

  @Test
  void anUnreadableSessionCountsAsInternal() {
    when(store.getTags("x")).thenReturn(Mono.error(new IllegalStateException("down")));

    StepVerifier.create(internal.checkInternal("x")).expectNext(true).verifyComplete();
  }

  @Test
  void theAnswerIsCachedUntilItExpiresOrIsInvalidated() {
    final Clock[] now = {Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)};
    internal.setClock(
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(final java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0].instant();
          }
        });
    tags("s", Map.of());

    internal.checkInternal("s").block();
    internal.checkInternal("s").block();
    verify(store, Mockito.times(1)).getTags("s");

    internal.invalidate("s");
    internal.checkInternal("s").block();
    verify(store, Mockito.times(2)).getTags("s");

    now[0] = Clock.fixed(Instant.EPOCH.plus(Duration.ofSeconds(60)), ZoneOffset.UTC);
    internal.checkInternal("s").block();
    verify(store, Mockito.times(3)).getTags("s");
  }

  @Test
  void sessionListsHideInternalSessionsUnlessAsked() {
    final SessionConfigStore configs = mock(SessionConfigStore.class);
    final SessionService service =
        new SessionService(
            configs,
            mock(ControlBusGateway.class),
            mock(WorkflowDefinitionStore.class),
            mock(PreparedWorkflowCache.class),
            mock(WorkflowPreparator.class));
    when(configs.getSessionIds()).thenReturn(Flux.just("a", "sys", "b"));
    when(configs.getTags(ArgumentMatchers.any()))
        .thenAnswer(
            call ->
                Mono.just(
                    "sys".equals(call.getArgument(0))
                        ? Map.of(InternalSessions.TAG, "true")
                        : Map.<String, String>of()));

    StepVerifier.create(service.getSessionIds(true).collectList())
        .assertNext(ids -> assertThat(ids).containsExactly("a", "sys", "b"))
        .verifyComplete();
    StepVerifier.create(service.getSessionIds(false).collectList())
        .assertNext(ids -> assertThat(ids).containsExactly("a", "sys", "b"))
        .verifyComplete();

    service.setInternalSessions(new InternalSessions(configs));
    StepVerifier.create(service.getSessionIds(false).collectList())
        .assertNext(ids -> assertThat(ids).containsExactly("a", "b"))
        .verifyComplete();
    StepVerifier.create(service.getSessionIds(true).collectList())
        .assertNext(ids -> assertThat(ids).containsExactly("a", "sys", "b"))
        .verifyComplete();
  }
}
