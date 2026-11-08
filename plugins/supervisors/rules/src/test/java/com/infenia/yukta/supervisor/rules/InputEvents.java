// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Input requests and their events for rule tests. */
@UtilityClass
class InputEvents {

  /** A fixed time. */
  /* default */ static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  /**
   * An approval request with the options approve and reject.
   *
   * @param risk its risk
   * @param context its context
   * @param version its version
   * @return the request
   */
  /* default */ static InputRequest approval(
      final InputRisk risk, final Map<String, Object> context, final long version) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "key",
            InputKind.APPROVAL,
            "Deploy build 42?",
            context,
            List.of(new InputOption("approve"), new InputOption("reject")),
            null,
            risk,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(5),
            TimeoutAction.FAIL,
            null,
            null,
            "ASK_INPUT");
    return new InputRequest(
        "r1",
        "key",
        "s",
        "wf",
        "e1",
        "ask",
        spec,
        InputState.OPEN,
        version,
        "auto",
        List.of("supervisor:*"),
        "ask a person",
        null,
        NOW,
        NOW);
  }

  /**
   * An {@code InputRequested} event.
   *
   * @param risk the risk of the request
   * @param context its context
   * @return the event
   */
  /* default */ static ExecutionEvent requested(
      final InputRisk risk, final Map<String, Object> context) {
    return new ExecutionEvent.InputRequested(approval(risk, context, 3), NOW);
  }

  /**
   * An {@code InputEscalated} event.
   *
   * @param risk the risk of the request
   * @return the event
   */
  /* default */ static ExecutionEvent escalated(final InputRisk risk) {
    return new ExecutionEvent.InputEscalated(approval(risk, Map.of(), 4), "tier timeout", NOW);
  }
}
