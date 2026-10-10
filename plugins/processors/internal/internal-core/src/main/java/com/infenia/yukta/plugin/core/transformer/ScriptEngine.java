// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import com.infenia.yukta.message.Message;

/**
 * Evaluates the SCRIPT mode of {@link MapperProcessor}. Implemented by the optional {@code
 * script-js} module so the GraalJS runtime stays out of builds that don't need it.
 */
public interface ScriptEngine {

  /**
   * Parses and caches a script ahead of time.
   *
   * @param script the script source
   */
  void precompile(String script);

  /**
   * Runs a script against a message.
   *
   * @param script the script source
   * @param message the message exposed to the script as {@code message}, {@code payload} and {@code
   *     metadata}
   * @return the script result as plain Java maps, lists and scalars
   */
  Object eval(String script, Message<?> message);
}
