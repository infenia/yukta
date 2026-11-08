// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.Outcome;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.supervisor.engine.DirectiveGate.Rejection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The checks on every command an engine returns, whatever the engine is. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class DirectiveGateTest {

  private static final SupervisorScope OWNS = new SupervisorScope.Workflow("w", "s");

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

  private final DirectiveGate gate = new DirectiveGate(clock);

  private static SupervisorCapabilities caps(final Set<String> initiative, final int perMinute) {
    return new SupervisorCapabilities(null, false, InputRisk.LOW, Set.of(), initiative, perMinute);
  }

  private static Directive.Command pause(final Cause cause, final String reason) {
    return new Directive.Command(
        "k", new ExecutionControlCommand.PauseWorkflowCommand("e"), cause, reason, null);
  }

  private DirectiveGate.Decision check(
      final Directive.Command command,
      final String session,
      final SupervisorCapabilities caps,
      final boolean enabled,
      final boolean dry) {
    return gate.check(
        "sup", command, new DirectiveGate.Target(session, "w", null), caps, OWNS, enabled, dry);
  }

  @Test
  void reactionToAnEventOnOwnedUnitNeedsNoReason() {
    final var decision =
        check(pause(Cause.event("e1"), null), "s", caps(Set.of(), 10), true, false);
    assertThat(decision.accepted()).isTrue();
    assertThat(decision.rejection()).isNull();
  }

  @Test
  void disabledSupervisorCommandItMayNotSendAndTargetItDoesNotOwnIsRefused() {
    assertThat(
            check(pause(Cause.event("e"), null), "s", caps(Set.of(), 10), false, false).rejection())
        .isEqualTo(Rejection.DISABLED);
    final var onlyStop =
        new SupervisorCapabilities(
            Set.of("StopWorkflowCommand"), false, InputRisk.LOW, Set.of(), Set.of(), 10);
    assertThat(check(pause(Cause.event("e"), null), "s", onlyStop, true, false).rejection())
        .isEqualTo(Rejection.NOT_ALLOWED);
    final var other =
        check(pause(Cause.event("e"), null), "other", caps(Set.of(), 10), true, false);
    assertThat(other.rejection()).isEqualTo(Rejection.NOT_OWNED);
    assertThat(other.outcome()).isEqualTo(Outcome.NOT_OWNED);
    assertThat(
            check(pause(Cause.event("e"), null), "s", caps(Set.of(), 10), false, false).outcome())
        .isNull();
  }

  @Test
  void commandWithNoTriggerNeedsReasonAndEntryInTheInitiativeList() {
    assertThat(
            check(
                    pause(Cause.initiative(), " "),
                    "s",
                    caps(Set.of("PauseWorkflowCommand"), 10),
                    true,
                    false)
                .rejection())
        .isEqualTo(Rejection.REASON_REQUIRED);
    assertThat(
            check(
                    pause(Cause.tick(), null),
                    "s",
                    caps(Set.of("PauseWorkflowCommand"), 10),
                    true,
                    false)
                .rejection())
        .isEqualTo(Rejection.REASON_REQUIRED);
    assertThat(
            check(pause(Cause.initiative(), "cpu high"), "s", caps(Set.of(), 10), true, false)
                .rejection())
        .isEqualTo(Rejection.INITIATIVE_NOT_ALLOWED);
    assertThat(
            check(
                    pause(Cause.initiative(), "cpu high"),
                    "s",
                    caps(Set.of("PauseWorkflowCommand"), 10),
                    true,
                    false)
                .accepted())
        .isTrue();
  }

  @Test
  void initiativeIsLimitedPerMinuteAndTheWindowSlides() {
    final var caps = caps(Set.of("PauseWorkflowCommand"), 2);
    assertThat(check(pause(Cause.initiative(), "r"), "s", caps, true, false).accepted()).isTrue();
    assertThat(check(pause(Cause.tick(), "r"), "s", caps, true, false).accepted()).isTrue();
    assertThat(check(pause(Cause.initiative(), "r"), "s", caps, true, false).rejection())
        .isEqualTo(Rejection.INITIATIVE_RATE_LIMITED);
    // a reaction to an event is not initiative
    assertThat(check(pause(Cause.event("e"), null), "s", caps, true, false).accepted()).isTrue();

    now = now.plus(Duration.ofSeconds(61));

    assertThat(check(pause(Cause.initiative(), "r"), "s", caps, true, false).accepted()).isTrue();
  }

  @Test
  void dryRunRecordsAndCountsNoInitiative() {
    final var caps = caps(Set.of("PauseWorkflowCommand"), 1);
    final var dry = check(pause(Cause.initiative(), "r"), "s", caps, true, true);
    assertThat(dry.accepted()).isFalse();
    assertThat(dry.recordOnly()).isTrue();
    assertThat(dry.rejection()).isNull();
    assertThat(check(pause(Cause.initiative(), "r"), "s", caps, true, false).accepted()).isTrue();
  }
}
