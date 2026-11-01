// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Valid requests for the tests of this package and of the control package. */
public final class InputFixtures {

  /** A moment the tests agree on. */
  public static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private InputFixtures() {}

  /**
   * A valid approval spec with no options.
   *
   * @return the spec
   */
  public static InputRequestSpec spec() {
    return spec(InputKind.APPROVAL, List.of());
  }

  /**
   * A valid spec of a kind.
   *
   * @param kind the kind
   * @param options the options
   * @return the spec
   */
  public static InputRequestSpec spec(final InputKind kind, final List<InputOption> options) {
    return new InputRequestSpec(
        InputKeys.of("s", "w", "n", "m", "approve"),
        kind,
        "Deploy to production?",
        Map.of("service", "api"),
        options,
        kind == InputKind.STRUCTURED ? "{\"type\":\"object\"}" : null,
        InputRisk.HIGH,
        Audience.CURRENT_TIER,
        Duration.ofMinutes(10),
        TimeoutAction.FAIL,
        null,
        null,
        "WAIT_FOR_SIGNAL");
  }

  /**
   * A valid open request.
   *
   * @return the request
   */
  public static InputRequest request() {
    return new InputRequest(
        "r1",
        spec().key(),
        "s",
        "w",
        "e1",
        "n",
        spec(),
        InputState.OPEN,
        0,
        "default",
        List.of("supervisor:*", "role:OPERATOR"),
        null,
        NOW.plusSeconds(600),
        NOW,
        NOW);
  }
}
