// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link SupervisorProvider}. */
@NoArgsConstructor
class SupervisorProviderTest {

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final SupervisorService service = mock(SupervisorService.class);

  private final SupervisorProvider provider = new SupervisorProvider(host, service);

  @Test
  void listShowsRunningSupervisorsBindingsAndTypes() {
    when(host.status())
        .thenReturn(
            List.of(
                new SupervisorInfo(
                    "b1", "SUPERVISOR", "T", 0, true, 20, 3, 1, 0, 0, "SESSION", "A", null)));
    when(service.bindings())
        .thenReturn(
            Flux.just(new SupervisorBinding("b1", "p", new SupervisorScope.Session("A"), true)));
    when(service.types()).thenReturn(List.of("rule-supervisor"));

    StepVerifier.create(provider.list())
        .assertNext(
            listing -> {
              assertThat(listing.running())
                  .singleElement()
                  .satisfies(
                      r -> {
                        assertThat(r.id()).isEqualTo("b1");
                        assertThat(r.scope()).isEqualTo("SESSION");
                        assertThat(r.scopeSessionId()).isEqualTo("A");
                        assertThat(r.applied()).isEqualTo(3);
                        assertThat(r.dryRun()).isTrue();
                      });
              assertThat(listing.bindings())
                  .singleElement()
                  .satisfies(b -> assertThat(b.scope()).isEqualTo("session:A"));
              assertThat(listing.types()).containsExactly("rule-supervisor");
            })
        .verifyComplete();
  }

  @Test
  void attachParsesTheScopeGeneratesAnIdAndDefaultsToEnabled() {
    when(service.attach(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(provider.attach("p", "workflow:A/w", null, " "))
        .assertNext(
            b -> {
              assertThat(b.id()).isNotBlank();
              assertThat(b.scope()).isEqualTo("workflow:A/w");
              assertThat(b.enabled()).isTrue();
            })
        .verifyComplete();
    StepVerifier.create(provider.attach("p", "global", false, " mine "))
        .assertNext(
            b -> {
              assertThat(b.id()).isEqualTo("mine");
              assertThat(b.enabled()).isFalse();
            })
        .verifyComplete();

    final ArgumentCaptor<SupervisorBinding> sent = ArgumentCaptor.forClass(SupervisorBinding.class);
    verify(service, org.mockito.Mockito.times(2)).attach(sent.capture());
    assertThat(sent.getAllValues().getFirst().scope())
        .isEqualTo(new SupervisorScope.Workflow("w", "A"));
  }

  @Test
  void attachWithoutBindingIdGeneratesOne() {
    when(service.attach(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(provider.attach("p", "global", true, null))
        .assertNext(b -> assertThat(b.id()).isNotBlank())
        .verifyComplete();
  }

  @Test
  void badScopeIsRejected() {
    StepVerifier.create(provider.attach("p", "everything", true, "b"))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void detachReportsTheRemovedBinding() {
    when(service.detach("b")).thenReturn(Mono.empty());

    StepVerifier.create(provider.detach("b"))
        .assertNext(
            d -> {
              assertThat(d.bindingId()).isEqualTo("b");
              assertThat(d.detached()).isTrue();
            })
        .verifyComplete();
  }
}
