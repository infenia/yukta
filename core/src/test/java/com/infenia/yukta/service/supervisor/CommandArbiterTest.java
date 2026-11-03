// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.supervisor.CommandArbiter.Decision;
import com.infenia.yukta.service.supervisor.CommandArbiter.Verdict;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/** Conflicting commands of supervisors are decided in a window; a stop is never held back. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class CommandArbiterTest {

  private static final Duration WINDOW = Duration.ofMillis(500);

  private final MutableClock clock = new MutableClock();
  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final CommandArbiter arbiter =
      new CommandArbiter(
          new YuktaProperties.Arbiter(WINDOW, Duration.ofSeconds(60), 2, Duration.ofMinutes(5)),
          clock,
          scheduler);

  private static ExecutionControlCommand pause(final String run) {
    return new ExecutionControlCommand.PauseWorkflowCommand(run);
  }

  private static ExecutionControlCommand resume(final String run) {
    return new ExecutionControlCommand.ResumeWorkflowCommand(run);
  }

  private Decision decided(final Mono<Decision> pending) {
    scheduler.advanceTimeBy(WINDOW.plusMillis(1));
    return pending.block(Duration.ofSeconds(1));
  }

  @Test
  void windowOfZeroChangesNothing() {
    final CommandArbiter off = CommandArbiter.disabled();

    assertThat(off.submit("a", 1, pause("e1")).block()).isEqualTo(Decision.SEND);
    assertThat(off.submit("b", 2, resume("e1")).block()).isEqualTo(Decision.SEND);
  }

  @Test
  void stopsAndCommandsThatCannotConflictAreSentAtOnce() {
    assertThat(
            arbiter
                .submit("a", 1, new ExecutionControlCommand.StopWorkflowCommand("e1", null))
                .block())
        .isEqualTo(Decision.SEND);
    assertThat(
            arbiter
                .submit("a", 1, new ExecutionControlCommand.StopNodeCommand("e1", "n", false, null))
                .block())
        .isEqualTo(Decision.SEND);
    assertThat(
            arbiter.submit("a", 1, new ExecutionControlCommand.RestartCommand("e1", "e2")).block())
        .isEqualTo(Decision.SEND);
  }

  @Test
  void theLowerOrderWinsPauseAgainstResume() {
    final Mono<Decision> pausing = arbiter.submit("slow", 5, pause("e1"));
    final Mono<Decision> resuming = arbiter.submit("fast", 1, resume("e1"));

    scheduler.advanceTimeBy(WINDOW.plusMillis(1));

    assertThat(resuming.block()).isEqualTo(Decision.SEND);
    final Decision lost = pausing.block();
    assertThat(lost.verdict()).isEqualTo(Verdict.ARBITRATED);
    assertThat(lost.reason()).contains("fast").contains("order 1");
  }

  @Test
  void theSameCommandFromSeveralSupervisorsIsSentOnce() {
    final Mono<Decision> first = arbiter.submit("a", 3, pause("e1"));
    final Mono<Decision> second = arbiter.submit("b", 3, pause("e1"));
    final Mono<Decision> third = arbiter.submit("c", 1, pause("e1"));

    scheduler.advanceTimeBy(WINDOW.plusMillis(1));

    // the lowest order is the winner of the side; the first to arrive is the one that is sent
    assertThat(first.block()).isEqualTo(Decision.SEND);
    assertThat(second.block().verdict()).isEqualTo(Verdict.COALESCED);
    assertThat(third.block().verdict()).isEqualTo(Verdict.COALESCED);
  }

  @Test
  void sideWithTheLowestOrderTakesItsAlliesAlong() {
    final Mono<Decision> pause1 = arbiter.submit("a", 2, pause("e1"));
    final Mono<Decision> pause2 = arbiter.submit("b", 3, pause("e1"));
    final Mono<Decision> resume1 = arbiter.submit("c", 1, resume("e1"));

    scheduler.advanceTimeBy(WINDOW.plusMillis(1));

    assertThat(resume1.block()).isEqualTo(Decision.SEND);
    assertThat(pause1.block().verdict()).isEqualTo(Verdict.ARBITRATED);
    assertThat(pause2.block().verdict()).isEqualTo(Verdict.ARBITRATED);
  }

  @Test
  void runsAndNodesAreDecidedSeparately() {
    final Mono<Decision> runPause = arbiter.submit("a", 1, pause("e1"));
    final Mono<Decision> otherRun = arbiter.submit("b", 2, resume("e2"));
    final Mono<Decision> nodePause =
        arbiter.submit("a", 1, new ExecutionControlCommand.PauseNodeCommand("e1", "n1"));
    final Mono<Decision> otherNode =
        arbiter.submit("b", 2, new ExecutionControlCommand.ResumeNodeCommand("e1", "n2"));

    scheduler.advanceTimeBy(WINDOW.plusMillis(1));

    for (final Mono<Decision> alone : java.util.List.of(runPause, otherRun, nodePause, otherNode)) {
      assertThat(alone.block()).isEqualTo(Decision.SEND);
    }
  }

  @Test
  void skipStepAndPriorityConflictsAreDecidedToo() {
    final Mono<Decision> skip =
        arbiter.submit("a", 1, new ExecutionControlCommand.SkipNodeCommand("e1", "n", true));
    final Mono<Decision> unskip =
        arbiter.submit("b", 2, new ExecutionControlCommand.SkipNodeCommand("e1", "n", false));
    final Mono<Decision> stepOn =
        arbiter.submit("a", 1, new ExecutionControlCommand.EnableStepModeCommand("e1", "n"));
    final Mono<Decision> stepOff =
        arbiter.submit("b", 2, new ExecutionControlCommand.DisableStepModeCommand("e1", "n"));
    final Mono<Decision> high =
        arbiter.submit("a", 1, new ExecutionControlCommand.SetPriorityCommand("e1", 9));
    final Mono<Decision> low =
        arbiter.submit("b", 2, new ExecutionControlCommand.SetPriorityCommand("e1", 1));
    final Mono<Decision> same =
        arbiter.submit("c", 3, new ExecutionControlCommand.SetPriorityCommand("e1", 9));

    scheduler.advanceTimeBy(WINDOW.plusMillis(1));

    assertThat(skip.block()).isEqualTo(Decision.SEND);
    assertThat(unskip.block().verdict()).isEqualTo(Verdict.ARBITRATED);
    assertThat(stepOn.block()).isEqualTo(Decision.SEND);
    assertThat(stepOff.block().verdict()).isEqualTo(Verdict.ARBITRATED);
    assertThat(high.block()).isEqualTo(Decision.SEND);
    assertThat(low.block().verdict()).isEqualTo(Verdict.ARBITRATED);
    assertThat(same.block().verdict()).isEqualTo(Verdict.COALESCED);
  }

  @Test
  void stopCancelsPauseOrResumeThatFollowsAndIsForgottenAfterSecond() {
    arbiter.submit("a", 1, new ExecutionControlCommand.StopWorkflowCommand("e1", null)).block();

    final Decision cancelled = arbiter.submit("b", 2, pause("e1")).block();
    final Decision skipIsFine =
        decided(
            arbiter.submit("b", 2, new ExecutionControlCommand.SkipNodeCommand("e1", "n", true)));
    assertThat(cancelled.verdict()).isEqualTo(Verdict.ARBITRATED);
    assertThat(cancelled.reason()).contains("stopped");
    assertThat(skipIsFine).isEqualTo(Decision.SEND);

    clock.advance(Duration.ofSeconds(2));
    assertThat(decided(arbiter.submit("b", 2, pause("e1")))).isEqualTo(Decision.SEND);
  }

  @Test
  void supervisorThatKeepsTurningTargetAroundIsBlockedForWhile() {
    // flap limit 2: pause, resume (1), pause (2), resume (3 > 2)
    assertThat(decided(arbiter.submit("a", 1, pause("e1")))).isEqualTo(Decision.SEND);
    assertThat(decided(arbiter.submit("a", 1, resume("e1")))).isEqualTo(Decision.SEND);
    assertThat(decided(arbiter.submit("a", 1, pause("e1")))).isEqualTo(Decision.SEND);
    final Decision blocked = decided(arbiter.submit("a", 1, resume("e1")));
    assertThat(blocked.verdict()).isEqualTo(Verdict.FLAPPING_BLOCKED);

    // blocked at once, without waiting for a window, for the whole cooldown
    final Decision still = arbiter.submit("a", 1, pause("e1")).block();
    assertThat(still.verdict()).isEqualTo(Verdict.FLAPPING_BLOCKED);
    assertThat(still.reason()).startsWith("blocked until");
    // other supervisors and other targets are not blocked
    assertThat(decided(arbiter.submit("b", 2, pause("e1")))).isEqualTo(Decision.SEND);
    assertThat(decided(arbiter.submit("a", 1, pause("e2")))).isEqualTo(Decision.SEND);

    clock.advance(Duration.ofMinutes(6));
    assertThat(decided(arbiter.submit("a", 1, pause("e1")))).isEqualTo(Decision.SEND);
  }

  @Test
  void turnAroundsOlderThanThePeriodAreForgotten() {
    assertThat(decided(arbiter.submit("a", 1, pause("e1")))).isEqualTo(Decision.SEND);
    assertThat(decided(arbiter.submit("a", 1, resume("e1")))).isEqualTo(Decision.SEND);
    clock.advance(Duration.ofSeconds(120));
    assertThat(decided(arbiter.submit("a", 1, pause("e1")))).isEqualTo(Decision.SEND);
    clock.advance(Duration.ofSeconds(120));
    assertThat(decided(arbiter.submit("a", 1, resume("e1")))).isEqualTo(Decision.SEND);
    clock.advance(Duration.ofSeconds(120));
    assertThat(decided(arbiter.submit("a", 1, pause("e1")))).isEqualTo(Decision.SEND);
  }

  @Test
  void sendingTheSameDirectionAgainIsNoTurnAround() {
    for (int i = 0; i < 6; i++) {
      assertThat(decided(arbiter.submit("a", 1, pause("e1")))).isEqualTo(Decision.SEND);
    }
  }

  @Test
  void pastTheBoundTheOldestWindowClosesEarly() {
    final Mono<Decision> oldest = arbiter.submit("a", 1, pause("run-0"));
    for (int i = 1; i <= 10_000; i++) {
      arbiter.submit("a", 1, pause("run-" + i));
    }

    // the oldest was decided without the window running out
    assertThat(oldest.block(Duration.ofSeconds(1))).isEqualTo(Decision.SEND);

    // the timer of the window that closed early finds nothing left to do
    scheduler.advanceTimeBy(WINDOW.plusMillis(1));
  }
}
