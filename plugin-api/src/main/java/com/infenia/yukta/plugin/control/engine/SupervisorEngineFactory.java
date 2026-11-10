// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;

/**
 * Makes the engine of a supervisor. An engine is a plugin like a processor: it declares a
 * descriptor and a settings schema, and the supervisor holds its settings. Found as a Spring bean.
 */
public interface SupervisorEngineFactory {

  /**
   * The type name in the catalog, such as {@code rules}, {@code http}, {@code process}.
   *
   * @return the type
   */
  String type();

  /**
   * What the engine is.
   *
   * @return the descriptor
   */
  EngineDescriptor describe();

  /**
   * The settings, as plugins describe theirs.
   *
   * @return the schema
   */
  List<ConfigField> getConfigSchema();

  /**
   * Checks the settings when the supervisor is saved; the message never repeats a secret.
   *
   * @param config the settings
   * @throws IllegalArgumentException if they are not valid
   */
  void validate(EngineConfig config);

  /**
   * Makes the engine.
   *
   * @param config the settings
   * @param context all the engine can reach
   * @return the engine
   */
  SupervisorEngine create(EngineConfig config, EngineContext context);
}
