// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import java.util.List;

/**
 * Supplies plugins that are not Spring beans, such as community plugins read from manifests at
 * startup. The registry asks every source once, at startup, and registers what it returns with the
 * same checks as a bean plugin (SPI version, no duplicate or shadowed type). A source returns only
 * the plugins the operator enabled: the registry's {@code yukta.plugins.enabled} filter, and its
 * {@code all}, do not apply to them.
 */
@FunctionalInterface
public interface PluginSource {

  /**
   * The plugins to register.
   *
   * @return the plugins; read once at startup
   */
  List<Plugin> plugins();
}
