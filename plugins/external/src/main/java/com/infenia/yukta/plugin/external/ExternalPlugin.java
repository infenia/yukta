// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginTier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * What both community plugin adapters report from their manifest. Abstract, so the engine never
 * sees it as a plugin of its own; {@link ExternalProcessorPlugin} and {@link
 * ExternalTerminalPlugin} add the run.
 */
public abstract class ExternalPlugin implements Plugin {

  /**
   * Time the engine allows beyond the plugin's own timeout, so the plugin's timeout fires first.
   */
  private static final Duration MARGIN = Duration.ofSeconds(5);

  /** The plugin's manifest. */
  protected final PluginManifest manifest;

  /** Runs the plugin's command. */
  protected final CommandRun run;

  /* default */ ExternalPlugin(final PluginManifest manifest, final CommandRun run) {
    this.manifest = manifest;
    this.run = run;
  }

  @Override
  public String getType() {
    return manifest.type();
  }

  @Override
  public PluginCategory getCategory() {
    return manifest.category();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        manifest.description(),
        "Community plugin in " + manifest.dir() + "; see its README.",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return manifest.config();
  }

  @Override
  public List<String> getOutputPorts() {
    return manifest.ports();
  }

  @Override
  public Duration getDefaultTimeout() {
    return manifest.timeout().plus(MARGIN);
  }

  @Override
  public PluginTier getTier() {
    return PluginTier.SPECIFIC;
  }

  @Override
  public boolean isRestricted() {
    return true;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return manifest.config().stream()
        .filter(f -> f.required() && (config == null || config.get(f.name()) == null))
        .findFirst()
        .<Mono<Void>>map(
            f ->
                Mono.error(
                    new IllegalArgumentException(
                        getType() + ": required config '" + f.name() + "' is missing")))
        .orElseGet(Mono::empty);
  }
}
