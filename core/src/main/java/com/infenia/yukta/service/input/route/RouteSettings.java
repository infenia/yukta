// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A route as it is written under {@code yukta.input.routes.<name>}: the name is the key.
 *
 * @param tiers the tiers in order, at least one
 * @param onFinalTimeout what happens when the last tier times out, or {@code null}
 * @since 0.1.0-alpha.4
 */
public record RouteSettings(List<Tier> tiers, @Nullable FinalAction onFinalTimeout) {

  /**
   * Gives the settings their name and checks them.
   *
   * @param name the key the route is written under
   * @return the route
   */
  public InputRoute named(final String name) {
    return new InputRoute(name, tiers, onFinalTimeout);
  }
}
