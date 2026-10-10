// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import java.util.Optional;

/**
 * Human-facing metadata of a plugin, kept apart from its runtime contract.
 *
 * <p>Output ports are not part of it: they depend on the node configuration and are used by
 * validation, so they stay on {@link Plugin#getOutputPorts(java.util.Map)}.
 *
 * @param description what the plugin does
 * @param usagePattern the expected configuration fields and input/output message structures
 * @param uiDesign how the node is drawn in the UI, empty for the default design
 */
public record PluginDescriptor(
    String description, String usagePattern, Optional<UiDesign> uiDesign) {

  /** The descriptor of a plugin that declares nothing. */
  public static final PluginDescriptor EMPTY = new PluginDescriptor("", "", Optional.empty());
}
