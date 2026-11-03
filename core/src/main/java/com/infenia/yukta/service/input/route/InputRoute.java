// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Where a request goes and in which order: tiers of responders, each with its own timeout, and what
 * happens when the last one gives up.
 *
 * @param name the route name
 * @param tiers the tiers in order, at least one
 * @param onFinalTimeout what happens when the last tier times out; {@code null} follows the
 *     request's own {@code onTimeout}
 * @since 0.1.0-alpha.4
 */
public record InputRoute(String name, List<Tier> tiers, @Nullable FinalAction onFinalTimeout) {

  /**
   * Checks the route.
   *
   * @param name the route name
   * @param tiers the tiers
   * @param onFinalTimeout the final action, or {@code null}
   */
  public InputRoute {
    Objects.requireNonNull(name, "name");
    if (tiers == null || tiers.isEmpty()) {
      throw new IllegalArgumentException("Route " + name + " needs at least one tier");
    }
    tiers = List.copyOf(tiers);
    final Tier last = tiers.getLast();
    if (last.type() != ResponderType.HUMAN && onFinalTimeout == null) {
      throw new IllegalArgumentException(
          "Route "
              + name
              + ": the last tier must be of type HUMAN or the route needs on-final-timeout");
    }
  }
}
