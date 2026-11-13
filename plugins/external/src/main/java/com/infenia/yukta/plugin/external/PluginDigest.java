// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * The digest an operator pins a plugin directory to ({@code TYPE@sha256:<digest>} in {@code
 * yukta.plugins.enabled}): SHA-256 over one line {@code <relative path>\0<hex SHA-256 of the
 * file>\n} per regular file, sorted by path with {@code /} separators. A symbolic link anywhere in
 * the directory is refused, since it could point outside it. {@code yukta-cli plugin validate}
 * computes the same value.
 */
@UtilityClass
public class PluginDigest {

  private static final String ALGORITHM = "SHA-256";

  /**
   * Computes the digest of a plugin directory.
   *
   * @param dir the plugin's directory
   * @return the digest, as {@code sha256:<64 hex digits>}
   * @throws IllegalStateException when the directory holds a symbolic link
   * @throws UncheckedIOException when a file cannot be read
   */
  public static String compute(final Path dir) {
    final MessageDigest all = digest(ALGORITHM);
    for (final Path file : files(dir)) {
      final String name = dir.relativize(file).toString().replace('\\', '/');
      final String line = name + '\0' + hex(file) + '\n';
      all.update(line.getBytes(StandardCharsets.UTF_8));
    }
    return "sha256:" + HexFormat.of().formatHex(all.digest());
  }

  private static List<Path> files(final Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      final List<Path> paths = walk.toList();
      for (final Path p : paths) {
        if (Files.isSymbolicLink(p)) {
          throw new IllegalStateException(
              "Plugin directory " + dir + " holds a symbolic link: " + dir.relativize(p));
        }
      }
      return paths.stream()
          .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          .sorted((a, b) -> rel(dir, a).compareTo(rel(dir, b)))
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String rel(final Path dir, final Path p) {
    return dir.relativize(p).toString().replace('\\', '/');
  }

  private static String hex(final Path file) {
    try {
      return HexFormat.of().formatHex(digest(ALGORITHM).digest(Files.readAllBytes(file)));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A digest; every Java runtime has SHA-256, so that name never fails. */
  /* default */ static MessageDigest digest(final String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
