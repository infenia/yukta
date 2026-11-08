// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import java.util.Set;

/**
 * What an engine is, for the catalog: its name, what it does, which signals drive it and whether it
 * can act on its own. The supervisor refuses a directive the descriptor does not declare.
 *
 * @param name the display name
 * @param description what it does
 * @param drivers the signal kinds it takes
 * @param canInitiate whether it may send a command with no triggering signal
 */
public record EngineDescriptor(
    String name, String description, Set<Signal.Kind> drivers, boolean canInitiate) {

  /** Keeps an unmodifiable copy. */
  public EngineDescriptor {
    drivers = Set.copyOf(drivers);
  }
}
