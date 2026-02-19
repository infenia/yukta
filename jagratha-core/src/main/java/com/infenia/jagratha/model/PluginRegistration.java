/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.ConfigKey;
import com.infenia.jagratha.validation.PluginName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Registration information for a plugin in a session.
 *
 * @param name the plugin name
 * @param pluginConfig the plugin-specific configuration
 */
@Schema(description = "Registration information for a plugin in a session")
public record PluginRegistration(
    @Schema(description = "The name of the plugin", example = "gradle") @PluginName String name,
    @Schema(description = "Configuration options for the plugin")
        @NotNull(message = "Plugin configuration is required")
        Map<
                @ConfigKey String,
                @NotNull(message = "Plugin configuration value cannot be null") Object>
            pluginConfig) {

  /** Compact constructor to ensure configuration is immutable. */
  public PluginRegistration {
    pluginConfig = pluginConfig != null ? Map.copyOf(pluginConfig) : Map.of();
  }
}
