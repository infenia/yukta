// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * A supervisor that keeps turning one target around is stopped; others and other targets are not.
 */
@NoArgsConstructor
class TurnAroundGuardTest {

  private static final Duration PERIOD = Duration.ofSeconds(60);
  private static final Duration COOLDOWN = Duration.ofMinutes(5);

  private Instant now = Instant.parse("2026-11-08T10:00:00Z");

  private final Clock clock =
      new Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };

  private final TurnAroundGuard guard =
      new TurnAroundGuard(new YuktaProperties.TurnAround(PERIOD, 2, COOLDOWN), clock);

  private static ExecutionControlCommand pause(final String run) {
    return new ExecutionControlCommand.PauseWorkflowCommand(run);
  }

  private static ExecutionControlCommand resume(final String run) {
    return new ExecutionControlCommand.ResumeWorkflowCommand(run);
  }

  @Test
  void aTargetTurnedAroundMoreThanTheLimitBlocksTheSupervisorForTheCooldown() {
    assertThat(guard.check("a", pause("e1"))).isEmpty();
    assertThat(guard.check("a", resume("e1"))).isEmpty(); // turn 1
    assertThat(guard.check("a", pause("e1"))).isEmpty(); // turn 2
    assertThat(guard.check("a", resume("e1")))
        .hasValueSatisfying(r -> assertThat(r).contains("e1")); // turn 3
    assertThat(guard.check("a", pause("e1"))).isPresent();

    now = now.plus(COOLDOWN).plusSeconds(1);

    assertThat(guard.check("a", pause("e1"))).isEmpty();
  }

  @Test
  void otherSupervisorsOtherTargetsAndCommandsThatDoNotFlipAreNotBlocked() {
    guard.check("a", pause("e1"));
    guard.check("a", resume("e1"));
    guard.check("a", pause("e1"));
    guard.check("a", resume("e1"));

    assertThat(guard.check("b", pause("e1"))).isEmpty();
    assertThat(guard.check("a", pause("e2"))).isEmpty();
    assertThat(guard.check("a", new ExecutionControlCommand.StopWorkflowCommand("e1", null)))
        .isEmpty();
  }

  @Test
  void turnsOlderThanThePeriodDoNotCount() {
    guard.check("a", pause("e1"));
    guard.check("a", resume("e1"));
    guard.check("a", pause("e1"));
    now = now.plus(PERIOD).plusSeconds(1);

    assertThat(guard.check("a", resume("e1"))).isEmpty();
  }

  @Test
  void nodeCommandsSkipStepAndPriorityTurnAroundToo() {
    final String[] directions = {"on", "off", "on", "off"};
    for (final String direction : directions) {
      final ExecutionControlCommand step =
          "on".equals(direction)
              ? new ExecutionControlCommand.EnableStepModeCommand("e1", "n")
              : new ExecutionControlCommand.DisableStepModeCommand("e1", "n");
      guard.check("a", step);
    }
    assertThat(guard.check("a", new ExecutionControlCommand.EnableStepModeCommand("e1", "n")))
        .isPresent();

    assertThat(guard.check("c", new ExecutionControlCommand.SkipNodeCommand("e1", "n", true)))
        .isEmpty();
    assertThat(guard.check("c", new ExecutionControlCommand.SkipNodeCommand("e1", "n", false)))
        .isEmpty();
    assertThat(guard.check("c", new ExecutionControlCommand.SetPriorityCommand("e1", 1))).isEmpty();
    assertThat(guard.check("c", new ExecutionControlCommand.PauseNodeCommand("e1", "n"))).isEmpty();
    assertThat(guard.check("c", new ExecutionControlCommand.ResumeNodeCommand("e1", "n")))
        .isEmpty();
  }

  @Test
  void settingsMustBePositive() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new YuktaProperties.TurnAround(Duration.ZERO, 1, COOLDOWN))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new YuktaProperties.TurnAround(PERIOD, 1, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Clock.systemUTC()).isNotNull();
    assertThat(
            new TurnAroundGuard(new YuktaProperties.TurnAround(PERIOD, 1, COOLDOWN))
                .check("a", pause("e")))
        .isEmpty();
  }
}
