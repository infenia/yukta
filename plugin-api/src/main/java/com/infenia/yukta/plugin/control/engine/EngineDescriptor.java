// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What an engine (a decision maker) is, for the catalog: its name, what it does, which signals
 * drive it and whether it can act on its own, and how the catalog shows it. The supervisor refuses
 * a directive the descriptor does not declare.
 *
 * @param name the display name
 * @param description what it does
 * @param drivers the signal kinds it takes
 * @param canInitiate whether it may send a command with no triggering signal
 * @param category where it sits in the catalog
 * @param usagePattern when to use it
 * @param uiDesign the icon and colour, empty for the default
 * @param exampleSettings settings that pass validation, shown as the example
 */
public record EngineDescriptor(
    String name,
    String description,
    Set<Signal.Kind> drivers,
    boolean canInitiate,
    SupervisorCategory category,
    String usagePattern,
    Optional<UiDesign> uiDesign,
    Map<String, Object> exampleSettings) {

  /**
   * An engine that runs on this server, with no usage text, icon or example.
   *
   * @param name the display name
   * @param description what it does
   * @param drivers the signal kinds it takes
   * @param canInitiate whether it may send a command with no triggering signal
   */
  public EngineDescriptor(
      final String name,
      final String description,
      final Set<Signal.Kind> drivers,
      final boolean canInitiate) {
    this(
        name,
        description,
        drivers,
        canInitiate,
        SupervisorCategory.ON_THIS_SERVER,
        "",
        Optional.empty(),
        Map.of());
  }

  /** Keeps unmodifiable copies. */
  public EngineDescriptor {
    drivers = Set.copyOf(drivers);
    exampleSettings = Collections.unmodifiableMap(new LinkedHashMap<>(exampleSettings));
  }
}
