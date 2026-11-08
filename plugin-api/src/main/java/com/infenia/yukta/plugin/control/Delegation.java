// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.time.Duration;
import java.util.Objects;

/**
 * How one kind of delegation is asked of the parents.
 *
 * @param mode how the parents are asked
 * @param timeout how long one parent has to answer; a parent that does not is silent
 */
public record Delegation(DelegationMode mode, Duration timeout) {

  /** The timeout when none is set. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** The default: ask in order, 30 seconds each. */
  public static final Delegation DEFAULT = new Delegation(DelegationMode.ORDERED, DEFAULT_TIMEOUT);

  /** Checks the parts. */
  public Delegation {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("A delegation timeout must be positive");
    }
  }
}
