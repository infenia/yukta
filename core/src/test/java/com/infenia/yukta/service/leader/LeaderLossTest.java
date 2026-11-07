// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.gateway.DefaultControlBusGateway;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class LeaderLossTest {

  @Test
  void losingTheLease_killsEveryLiveRun() {
    final LeaderElection election = mock(LeaderElection.class);
    final ExecutionControlStore registry = mock(ExecutionControlStore.class);
    final DefaultControlBusGateway gateway = mock(DefaultControlBusGateway.class);
    final ExecutionControl run = mock(ExecutionControl.class);
    when(run.executionId()).thenReturn("e1");
    when(registry.findAll()).thenReturn(List.of(run));

    final LeaderLoss loss = new LeaderLoss(election, registry, gateway);
    loss.afterSingletonsInstantiated();
    final ArgumentCaptor<Runnable> hook = ArgumentCaptor.forClass(Runnable.class);
    verify(election).onLoss(hook.capture());
    hook.getValue().run();

    verify(gateway).killLocally(eq("e1"), any());
    verify(gateway).cancelRunningExecutions();
  }

  @Test
  @ExpectedErrorLog("ERROR com.infenia.yukta.service.leader.LeaderLoss - ")
  void killThatFails_doesNotSpareTheOtherRuns() {
    final LeaderElection election = mock(LeaderElection.class);
    final ExecutionControlStore registry = mock(ExecutionControlStore.class);
    final DefaultControlBusGateway gateway = mock(DefaultControlBusGateway.class);
    final ExecutionControl first = mock(ExecutionControl.class);
    final ExecutionControl second = mock(ExecutionControl.class);
    when(first.executionId()).thenReturn("e1");
    when(second.executionId()).thenReturn("e2");
    when(registry.findAll()).thenReturn(List.of(first, second));
    doThrow(new IllegalStateException("boom")).when(gateway).killLocally(eq("e1"), any());

    new LeaderLoss(election, registry, gateway).stopAll();

    verify(gateway).killLocally(eq("e2"), any());
    verify(gateway).cancelRunningExecutions();
  }
}
