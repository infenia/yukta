// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

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
import org.jspecify.annotations.Nullable;

/** Input requests for the model plane tests. */
@UtilityClass
class InputRequests {

  /** A fixed time. */
  /* default */ static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  /**
   * A request.
   *
   * @param kind its kind
   * @param risk its risk
   * @param prompt its prompt
   * @param context its context
   * @param options its options
   * @return the request, at version 2
   */
  /* default */ static InputRequest request(
      final InputKind kind,
      final InputRisk risk,
      final String prompt,
      final Map<String, Object> context,
      final List<InputOption> options) {
    final @Nullable String schema = kind == InputKind.STRUCTURED ? "{\"type\":\"object\"}" : null;
    final InputRequestSpec spec =
        new InputRequestSpec(
            "key",
            kind,
            prompt,
            context,
            options,
            schema,
            risk,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(5),
            TimeoutAction.FAIL,
            null,
            null,
            "MCP_TOOL_CALL");
    return new InputRequest(
        "r1",
        "key",
        "s",
        "wf",
        "e1",
        "ask",
        spec,
        InputState.OPEN,
        2,
        "auto",
        List.of("supervisor:*"),
        null,
        null,
        NOW,
        NOW);
  }

  /**
   * A request with the options deploy and hold.
   *
   * @param risk its risk
   * @return the request
   */
  /* default */ static InputRequest choice(final InputRisk risk) {
    return request(
        InputKind.CHOICE,
        risk,
        "Deploy build 42 now or hold it?",
        Map.of("environment", "staging"),
        List.of(new InputOption("deploy", "Deploy now"), new InputOption("hold", "Hold")));
  }
}
