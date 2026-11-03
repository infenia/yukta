// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One supervisor's answer in a tier that decides by {@code QUORUM} or {@code UNANIMOUS}. A vote
 * does not end the request; the broker counts the votes and resolves it when enough agree.
 *
 * @param voter the id of the supervisor that voted
 * @param decision what it decided
 * @param confidence how sure it was, or {@code null}
 * @param at when it voted
 * @since 0.1.0-alpha.4
 */
public record Vote(String voter, String decision, @Nullable Double confidence, Instant at) {

  /**
   * Checks the parts.
   *
   * @param voter the supervisor id
   * @param decision what it decided
   * @param confidence how sure it was, or {@code null}
   * @param at when it voted
   */
  public Vote {
    InputLimits.requireId("voter", voter);
    InputLimits.requireId("decision", decision);
    InputLimits.requireConfidence(confidence);
    Objects.requireNonNull(at, "at");
  }
}
