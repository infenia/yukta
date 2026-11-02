// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The {@code engine.echoOutput} setting: whether a node's output message is copied into the run log
 * when the plugin did not log it live.
 *
 * <p>A node that sets it gets what it says. Otherwise a node with secret config (a field the plugin
 * declares secret, or a key at any depth named like a credential) is not echoed, since its output
 * may carry the secret.
 */
@UtilityClass
public class EchoOutput {

  /**
   * Tells the decorator whether the node has logged its output already, which stops the echo.
   *
   * @param plugin the node's plugin
   * @param config the node config
   * @param loggedLive whether the plugin logged live
   * @return {@code loggedLive}, or always true when the node's output must not be echoed
   * @throws IllegalArgumentException if {@code engine.echoOutput} is not a boolean
   */
  public BooleanSupplier loggedLive(
      final Plugin plugin, final Map<String, Object> config, final BooleanSupplier loggedLive) {
    return enabled(plugin, config) ? loggedLive : () -> true;
  }

  /* default */ boolean enabled(final Plugin plugin, final Map<String, Object> config) {
    final Object engine = config.get("engine");
    final Object set = engine instanceof Map<?, ?> map ? map.get("echoOutput") : null;
    if (set == null) {
      return !hasSecretConfig(plugin, config);
    }
    if (set instanceof final Boolean flag) {
      return flag;
    }
    throw new IllegalArgumentException("engine.echoOutput must be true or false");
  }

  private boolean hasSecretConfig(final Plugin plugin, final Map<String, Object> config) {
    final Set<String> declared =
        plugin.getConfigSchema().stream()
            .filter(field -> field.type() == ConfigField.Type.SECRET)
            .map(ConfigField::name)
            .collect(Collectors.toSet());
    return config.keySet().stream().anyMatch(declared::contains) || hasSecretKey(config);
  }

  private boolean hasSecretKey(final Object value) {
    if (value instanceof final Map<?, ?> map) {
      return map.entrySet().stream()
          .anyMatch(
              e ->
                  ConfigRedactor.isSecretName(String.valueOf(e.getKey()))
                      || hasSecretKey(e.getValue()));
    }
    return value instanceof final List<?> list && list.stream().anyMatch(EchoOutput::hasSecretKey);
  }
}
