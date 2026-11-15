// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.core.PluginSource;
import com.infenia.yukta.plugin.mcp.McpClientGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Community plugins from {@code yukta.plugins.dir}. The bean always exists (a condition would be
 * fixed at build time in the native image); without the setting it supplies no plugins.
 */
@AutoConfiguration
public class ExternalPluginsAutoConfiguration {

  /** Default constructor. */
  public ExternalPluginsAutoConfiguration() {
    super();
  }

  /**
   * The community plugins in {@code yukta.plugins.dir}.
   *
   * @param dir the plugins directory, or blank for none
   * @param enabled the enabled types, some pinned with {@code @sha256:...}
   * @param maxOutputBytes the most a plugin may write to stdout for one message
   * @param maxOutputs the most output objects a plugin may write for one message
   * @param runtime the container CLI for {@code run.image} plugins
   * @param env reads {@code yukta.plugins.container.args.<TYPE>}
   * @param gateway runs the commands
   * @param mcpGateway reaches {@code run.mcp} plugins over HTTP
   * @param resolver resolves the node configuration per message
   * @return the plugin source
   */
  @Bean
  public PluginSource externalPlugins(
      @Value("${yukta.plugins.dir:}") final String dir,
      @Value("${yukta.plugins.enabled:}") final List<String> enabled,
      @Value("${yukta.plugins.external.max-output-bytes:1048576}") final long maxOutputBytes,
      @Value("${yukta.plugins.external.max-outputs:1000}") final int maxOutputs,
      @Value("${yukta.plugins.container.runtime:docker}") final String runtime,
      final Environment env,
      final ProcessExecutorGateway gateway,
      final McpClientGateway mcpGateway,
      final VariableResolver resolver) {
    return new ExternalPlugins(
        dir.isBlank() ? null : Path.of(dir),
        enabled,
        gateway,
        resolver,
        maxOutputBytes,
        maxOutputs,
        new Containers(runtime, containerArgs(env)),
        mcpGateway);
  }

  /**
   * {@code yukta.plugins.container.args.<TYPE>}: the extra {@code run} arguments per plugin type.
   *
   * @param env the server's settings
   * @return type to arguments
   */
  /* default */ static Map<String, List<String>> containerArgs(final Environment env) {
    return Binder.get(env)
        .bind("yukta.plugins.container.args", Bindable.mapOf(String.class, String[].class))
        .map(
            bound ->
                bound.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))))
        .orElse(Map.of());
  }
}
