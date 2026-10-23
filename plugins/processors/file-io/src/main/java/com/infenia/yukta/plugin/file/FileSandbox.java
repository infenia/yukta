// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves configured file paths and refuses any that escape the configured base directory.
 *
 * <p>{@code baseDir} is chosen by the workflow author, so on its own the sandbox only protects
 * against a path that comes from a message. The operator can set a root with the system property
 * {@value #ROOT_PROPERTY} (or the environment variable {@code YUKTA_FILES_ALLOWED_ROOT}); every
 * {@code baseDir} must then lie inside it, whatever the author writes.
 */
public final class FileSandbox {

  /** System property naming the directory every {@code baseDir} must be inside. */
  public static final String ROOT_PROPERTY = "yukta.files.allowed-root";

  /** Environment variable with the same meaning as {@value #ROOT_PROPERTY}. */
  private static final String ROOT_ENV = "YUKTA_FILES_ALLOWED_ROOT";

  private FileSandbox() {}

  /**
   * Resolves the file for a message: config {@code path}, else payload field {@code path}.
   *
   * @param config plugin config; {@code baseDir} is required
   * @param payload the message payload
   * @return the normalized absolute path inside {@code baseDir}
   * @throws IOException if the path cannot be resolved on disk
   */
  public static Path resolve(final Map<String, Object> config, final Object payload)
      throws IOException {
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
    refuseLinks(base, file, requested);
    return file;
  }

  /** Refuses a baseDir or path that reaches outside its root through a symbolic link. */
  private static void refuseLinks(final Path base, final Path file, final Object requested)
      throws IOException {
    // A baseDir that is itself a link must not lead out of the operator's root.
    final Path root = root();
    if (root != null && !realOfNearestExisting(base).startsWith(realOfNearestExisting(root))) {
      throw new IllegalArgumentException("baseDir leaves the operator's root via symlink: " + base);
    }
    // Symbolic links must not lead out of baseDir either.
    // The final open also refuses a link (NOFOLLOW_LINKS); a directory swapped for a link between
    // this check and the open is not caught.
    if (!realOfNearestExisting(file).startsWith(realOfNearestExisting(base))) {
      throw new IllegalArgumentException("path escapes baseDir via symlink: " + requested);
    }
  }

  private static Path realOfNearestExisting(final Path path) throws IOException {
    Path probe = path;
    while (!Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
      probe = java.util.Objects.requireNonNull(probe.getParent(), "no existing ancestor");
    }
    return probe.toRealPath();
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
    final Path dir = Path.of(String.valueOf(base)).toAbsolutePath().normalize();
    final Path root = root();
    if (root != null && !dir.startsWith(root)) {
      throw new IllegalArgumentException(
          "baseDir " + dir + " is outside the operator's root (" + ROOT_PROPERTY + ")");
    }
    return dir;
  }

  /** The operator's root, or null if none is set. */
  private static Path root() {
    final String root = System.getProperty(ROOT_PROPERTY, System.getenv(ROOT_ENV));
    return root == null || root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize();
  }
}
