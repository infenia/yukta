// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

import com.infenia.yukta.plugin.input.Audience;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One step of a route: who may answer, for how long, and how they decide.
 *
 * @param name the tier name, shown on the request
 * @param responders the selectors of the responders ({@code supervisor:*}, {@code supervisor:<id>},
 *     {@code supervisor:above}, {@code role:OPERATOR}, {@code key:<name>}, {@code external:<bean>})
 * @param timeout how long the tier waits before the request moves on, or {@code null} for no limit
 * @param mode how the responders decide; {@code FIRST} when {@code null}
 * @param quorum equal decisions that win in {@code QUORUM} mode, at least two
 * @param minConfidence a supervisor answer below this is advice, not an answer
 * @param type who the tier is meant for; derived from the responders when {@code null}
 * @param settings settings for the external responders of the tier
 * @since 0.1.0-alpha.4
 */
public record Tier(
    String name,
    List<String> responders,
    @Nullable Duration timeout,
    @Nullable Mode mode,
    int quorum,
    double minConfidence,
    @Nullable ResponderType type,
    @Nullable Map<String, Object> settings) {

  /** Start of every message about a tier. */
  private static final String TIER = "Tier ";

  /** The fewest equal decisions a quorum can be. */
  private static final int MIN_QUORUM = 2;

  /**
   * Checks the tier and fills in the mode, the type and the settings.
   *
   * @param name the tier name
   * @param responders the selectors
   * @param timeout how long it waits, or {@code null}
   * @param mode how it decides
   * @param quorum equal decisions that win
   * @param minConfidence the lowest confidence of a supervisor answer
   * @param type who it is meant for
   * @param settings settings for external responders
   */
  public Tier {
    Objects.requireNonNull(name, "name");
    if (!name.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("A tier name must match [A-Za-z0-9._-]{1,64}: " + name);
    }
    if (responders == null || responders.isEmpty()) {
      throw new IllegalArgumentException(TIER + name + " needs at least one responder");
    }
    // the same check a request audience gets
    responders = new Audience(responders).selectors();
    if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
      throw new IllegalArgumentException(TIER + name + ": timeout must be positive");
    }
    mode = mode == null ? Mode.FIRST : mode;
    if (mode == Mode.QUORUM && quorum < MIN_QUORUM) {
      throw new IllegalArgumentException(TIER + name + ": quorum must be at least 2");
    }
    if (minConfidence < 0 || minConfidence > 1) {
      throw new IllegalArgumentException(TIER + name + ": minConfidence must be 0 to 1");
    }
    if (type == null) {
      type =
          responders.stream().allMatch(r -> r.startsWith("supervisor:"))
              ? ResponderType.AUTO
              : ResponderType.HUMAN;
    }
    settings =
        settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
  }

  /**
   * Whether the tier decides by counting the votes of supervisors.
   *
   * @return true for {@code QUORUM} and {@code UNANIMOUS}
   */
  public boolean votes() {
    return mode != Mode.FIRST;
  }
}
