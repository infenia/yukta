// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One step of the way a request came to whoever answers it now: a supervisor that asked for
 * approval, a parent asked before a person ({@code allow}, {@code deny}, {@code no-opinion} or
 * {@code silent}), or a tier or responder that handed it up ({@code escalated}).
 *
 * @param who the supervisor, key or tier
 * @param said what it did or answered
 * @param reason why, or {@code null}
 * @param at when
 * @since 0.1.0-alpha.4
 */
public record Asked(String who, String said, @Nullable String reason, Instant at) {

  /** The most steps a request keeps; older ones are dropped first. */
  public static final int MAX_STEPS = 20;

  /**
   * Checks the parts.
   *
   * @param who the supervisor, key or tier
   * @param said what it did or answered
   * @param reason why, or {@code null}
   * @param at when
   */
  public Asked {
    InputLimits.requireId("who", who);
    InputLimits.requireId("said", said);
    InputLimits.optionalText("reason", reason, InputLimits.TEXT_MAX_BYTES);
    Objects.requireNonNull(at, "at");
  }
}
