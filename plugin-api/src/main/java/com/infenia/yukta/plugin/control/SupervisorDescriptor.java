// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import com.infenia.yukta.plugin.core.UiDesign;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the catalog shows about a supervisor type, like a plugin's descriptor.
 *
 * @param displayName the name shown to people
 * @param description what the supervisor does, in a sentence or two
 * @param usagePattern when to use it
 * @param category where it sits in the catalog
 * @param uiDesign the icon and colour, empty for the default
 * @param exampleSettings settings that pass validation, shown as the example
 */
public record SupervisorDescriptor(
    String displayName,
    String description,
    String usagePattern,
    SupervisorCategory category,
    Optional<UiDesign> uiDesign,
    Map<String, Object> exampleSettings) {

  /** Rejects missing parts and keeps an immutable copy of the example. */
  public SupervisorDescriptor {
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(usagePattern, "usagePattern");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(uiDesign, "uiDesign");
    exampleSettings =
        exampleSettings == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(exampleSettings));
  }

  /** Returns a fresh unmodifiable copy of the example, so the stored map is never exposed. */
  @Override
  public Map<String, Object> exampleSettings() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(exampleSettings));
  }
}
