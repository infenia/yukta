// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import java.nio.file.Path;
import java.util.Map;

/** Resolves configured file paths and refuses any that escape the configured base directory. */
public final class FileSandbox {

  private FileSandbox() {}

  /**
   * Resolves the file for a message: config {@code path}, else payload field {@code path}.
   *
   * @param config plugin config; {@code baseDir} is required
   * @param payload the message payload
   * @return the normalized absolute path inside {@code baseDir}
   */
  public static Path resolve(final Map<String, Object> config, final Object payload) {
    final Path base = baseDir(config);
    Object requested = config.get("path");
    if (requested == null && payload instanceof Map<?, ?> map) {
      requested = map.get("path");
    }
    if (requested == null) {
      throw new IllegalArgumentException("path is required (config or payload.path)");
    }
    final Path file = base.resolve(String.valueOf(requested)).normalize();
    if (!file.startsWith(base)) {
      throw new IllegalArgumentException("path escapes baseDir: " + requested);
    }
    return file;
  }

  /**
   * Returns the validated base directory.
   *
   * @param config plugin config
   * @return the normalized absolute base directory
   */
  public static Path baseDir(final Map<String, Object> config) {
    final Object base = config.get("baseDir");
    if (base == null || String.valueOf(base).isBlank()) {
      throw new IllegalArgumentException("baseDir is required");
    }
    return Path.of(String.valueOf(base)).toAbsolutePath().normalize();
  }
}
