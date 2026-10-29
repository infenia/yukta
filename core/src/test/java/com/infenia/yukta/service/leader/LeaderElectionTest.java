// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.log.ExecutionRecovery;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import java.util.List;
import java.util.function.Consumer;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "unchecked"})
class LeaderElectionTest {

  private final LeaderElection election = mock(LeaderElection.class);
  private final ExecutionRecovery recovery = mock(ExecutionRecovery.class);
  private final ExecutionResumer resumer = mock(ExecutionResumer.class);

  @Test
  void singleServer_isAlwaysLeader() {
    final LeaderElection single = new SingleServerLeader();

    assertThat(single.isLeader()).isTrue();
    assertThat(single.recoversOnTakeover()).isFalse();
    single.onTakeover(() -> {});
    assertThat(new NotLeaderException().getMessage()).contains("standby");
  }

  @Test
  void onTakeover_theLogIsRecovered() {
    new TakeoverRecovery(election, recovery, resumer, false).afterSingletonsInstantiated();

    final ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
    verify(election).onTakeover(action.capture());
    action.getValue().run();
    verify(recovery).recover();
    verify(recovery, never()).onRecovered(any());
  }

  @Test
  void autoResume_resumesWhatRecoveryFound_skipsFailures() {
    when(resumer.resume("a")).thenReturn(Mono.just("a2"));
    when(resumer.resume("b")).thenReturn(Mono.error(new IllegalStateException("cannot")));
    when(resumer.resume("c")).thenReturn(Mono.just("c2"));
    final ArgumentCaptor<Consumer<List<String>>> listener = ArgumentCaptor.forClass(Consumer.class);
    new TakeoverRecovery(election, recovery, resumer, true);
    verify(recovery).onRecovered(listener.capture());

    listener.getValue().accept(List.of("a", "b", "c"));

    org.awaitility.Awaitility.await()
        .untilAsserted(
            () -> {
              verify(resumer).resume("a");
              verify(resumer).resume("b");
              verify(resumer).resume("c");
            });
  }
}
