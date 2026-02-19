package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
    @Schema(description = "The name of the plugin", example = "gradle")
        @NotBlank(message = "Plugin name is required")
        String name,
    @Schema(description = "Configuration options for the plugin")
        @NotNull(message = "Plugin configuration is required")
        Map<
                @NotBlank(message = "Plugin configuration key cannot be blank") String,
                @NotNull(message = "Plugin configuration value cannot be null") Object>
            pluginConfig) {

  /** Compact constructor to ensure configuration is immutable. */
  public PluginRegistration {
    pluginConfig = pluginConfig != null ? Map.copyOf(pluginConfig) : Map.of();
  }
}
