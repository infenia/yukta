// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.ObjectMapper;

/**
 * The agent harness presets: the ones shipped on the classpath ({@code agent-harnesses/*.json})
 * plus any {@code *.json} in the operator's directory ({@code yukta.ai.presets-dir}); a preset in
 * the directory replaces a shipped one with the same id.
 */
public final class AgentHarnesses {

  /** The presets by id; filled once by the constructor, sorted for stable listings. */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private final Map<String, AgentPreset> presets = new TreeMap<>();

  /**
   * Loads the presets.
   *
   * @param json the JSON mapper
   * @param dir the operator's directory, or blank for none
   * @throws IOException when a preset cannot be read or is invalid
   */
  public AgentHarnesses(final ObjectMapper json, final String dir) throws IOException {
    for (final Resource resource :
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:agent-harnesses/*.json")) {
      try (InputStream in = resource.getInputStream()) {
        add(AgentPreset.parse(json.readTree(in)));
      }
    }
    if (dir != null && !dir.isBlank()) {
      try (Stream<Path> files = Files.list(Path.of(dir))) {
        for (final Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
          add(AgentPreset.parse(json.readTree(Files.readString(file))));
        }
      }
    }
  }

  private void add(final AgentPreset preset) {
    presets.put(preset.id(), preset);
  }

  /**
   * Finds a preset.
   *
   * @param id the preset id
   * @return the preset, or null
   */
  public AgentPreset get(final String id) {
    return presets.get(id);
  }

  /**
   * All presets, by id.
   *
   * @return the presets
   */
  public Collection<AgentPreset> all() {
    return presets.values();
  }
}
