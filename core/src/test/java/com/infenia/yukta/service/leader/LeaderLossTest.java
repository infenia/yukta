// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class LeaderLossTest {

  @Test
  void losingTheLease_killsEveryLiveRun() {
    final LeaderElection election = mock(LeaderElection.class);
    final ExecutionControlStore registry = mock(ExecutionControlStore.class);
    final ControlBusGateway gateway = mock(ControlBusGateway.class);
    final ExecutionControl run = mock(ExecutionControl.class);
    when(run.executionId()).thenReturn("e1");
    when(registry.findAll()).thenReturn(List.of(run));
    when(gateway.stopExecution(eq("e1"), any(), eq(StopMode.KILL))).thenReturn(Mono.just("e1"));

    final LeaderLoss loss = new LeaderLoss(election, registry, gateway);
    loss.afterSingletonsInstantiated();
    final ArgumentCaptor<Runnable> hook = ArgumentCaptor.forClass(Runnable.class);
    verify(election).onLoss(hook.capture());
    hook.getValue().run();

    verify(gateway).stopExecution(eq("e1"), any(), eq(StopMode.KILL));
  }
}
