// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import org.junit.jupiter.api.Test;

class HoldLedgerTest {

  private final HoldLedger ledger = new HoldLedger();

  @Test
  void aPauseIsHeldUntilTheRunIsResumedOrStopped() {
    ledger.applied("s", new PauseWorkflowCommand("a"));
    ledger.applied("s", new PauseWorkflowCommand("b"));
    ledger.applied("s", new PauseWorkflowCommand("c"));
    ledger.applied("other", new PauseWorkflowCommand("z"));
    assertThat(ledger.held("s")).containsExactly("a", "b", "c");

    ledger.applied("s", new ResumeWorkflowCommand("a"));
    ledger.applied("s", new StopWorkflowCommand("b", "done"));
    assertThat(ledger.held("s")).containsExactly("c");
    assertThat(ledger.held("other")).containsExactly("z");
  }

  @Test
  void otherCommandsAndUnknownSupervisorsChangeNothing() {
    ledger.applied("s", new PauseNodeCommand("a", "n"));
    ledger.release("nobody", "a");
    assertThat(ledger.held("s")).isEmpty();
    assertThat(ledger.held("nobody")).isEmpty();
  }

  @Test
  void aSupervisorHoldsAtMostAThousandRuns() {
    for (int i = 0; i < HoldLedger.MAX_PER_SUPERVISOR + 5; i++) {
      ledger.applied("s", new PauseWorkflowCommand("r" + i));
    }
    assertThat(ledger.held("s")).hasSize(HoldLedger.MAX_PER_SUPERVISOR);
  }
}
