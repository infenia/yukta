// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
class HoldLedgerTest {

  private final HoldLedger ledger = new HoldLedger();

  @Test
  void pauseIsHeldUntilTheRunIsResumedOrStopped() {
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
  void releaseEverywhereForgetsTheRunForEverySupervisor() {
    ledger.applied("s", new PauseWorkflowCommand("a"));
    ledger.applied("t", new PauseWorkflowCommand("a"));
    ledger.applied("t", new PauseWorkflowCommand("b"));

    ledger.releaseEverywhere("a");

    assertThat(ledger.held("s")).isEmpty();
    assertThat(ledger.held("t")).containsExactly("b");
  }

  @Test
  void otherCommandsAndUnknownSupervisorsChangeNothing() {
    ledger.applied("s", new PauseNodeCommand("a", "n"));
    ledger.release("nobody", "a");
    assertThat(ledger.held("s")).isEmpty();
    assertThat(ledger.held("nobody")).isEmpty();
  }

  @Test
  void supervisorHoldsAtMostThousandRunsAndTheNewestPauseIsKept() {
    for (int i = 0; i < HoldLedger.MAX_PER_SUPERVISOR + 5; i++) {
      ledger.applied("s", new PauseWorkflowCommand("r" + i));
    }
    assertThat(ledger.held("s"))
        .hasSize(HoldLedger.MAX_PER_SUPERVISOR)
        .doesNotContain("r0")
        .contains("r" + (HoldLedger.MAX_PER_SUPERVISOR + 4));
  }

  @Test
  void everyChangeReachesTheListenerInOrderAndOnlyWhenSomethingChanged() {
    final List<HoldLedger.Change> changes = new ArrayList<>();
    ledger.onChange(changes::add);

    ledger.applied("s", new PauseWorkflowCommand("a"));
    ledger.applied("s", new PauseNodeCommand("a", "n"));
    ledger.applied("s", new ResumeWorkflowCommand("a"));
    ledger.applied("s", new ResumeWorkflowCommand("a"));
    ledger.applied("t", new PauseWorkflowCommand("b"));
    ledger.releaseEverywhere("b");
    ledger.releaseEverywhere("never-held");

    assertThat(changes)
        .containsExactly(
            new HoldLedger.Change("s", "a", true),
            new HoldLedger.Change("s", "a", false),
            new HoldLedger.Change("t", "b", true),
            new HoldLedger.Change(null, "b", false));
  }

  @Test
  void theRunDroppedOverTheCapIsReleasedForTheListener() {
    final List<HoldLedger.Change> changes = new ArrayList<>();
    for (int i = 0; i < HoldLedger.MAX_PER_SUPERVISOR; i++) {
      ledger.applied("s", new PauseWorkflowCommand("r" + i));
    }
    ledger.onChange(changes::add);

    ledger.applied("s", new PauseWorkflowCommand("newest"));

    assertThat(changes)
        .containsExactly(
            new HoldLedger.Change("s", "r0", false), new HoldLedger.Change("s", "newest", true));
  }

  @Test
  void loadPutsBackAHoldWithoutTellingTheListener() {
    final List<HoldLedger.Change> changes = new ArrayList<>();
    ledger.onChange(changes::add);

    ledger.load("s", "a");

    assertThat(ledger.held("s")).containsExactly("a");
    assertThat(changes).isEmpty();
  }
}
