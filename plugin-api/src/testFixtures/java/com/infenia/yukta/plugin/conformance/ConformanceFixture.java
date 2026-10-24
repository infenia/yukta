// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.conformance;

import java.util.List;
import java.util.Map;

/**
 * Tells {@link PluginConformance} how to run one plugin without the outside world. The module that
 * sweeps every plugin lists one fixture per plugin type.
 */
public interface ConformanceFixture {

  /**
   * The plugin type this fixture is for.
   *
   * @return the value of {@code Plugin.getType()}
   */
  String type();

  /**
   * A minimal configuration that is valid and touches nothing outside the JVM (stub URLs, temp
   * directories, in-memory stores). It must set every required field of the plugin's schema.
   *
   * @return the node configuration
   */
  Map<String, Object> config();

  /**
   * Payloads a processor or terminal receives in the happy-path run.
   *
   * @return the payloads
   */
  default List<Object> inputs() {
    return List.of("conformance");
  }
}
