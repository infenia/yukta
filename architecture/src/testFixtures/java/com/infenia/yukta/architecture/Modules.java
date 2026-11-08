// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Optional;

/**
 * Tells which Gradle module a class comes from. Modules share packages, so the module is read from
 * the location the class was loaded from: {@code <module dir>/build/...} (classes dir or jar).
 */
public final class Modules {

  private Modules() {}

  /** The class file's location before any jar entry, or empty for a class without a source. */
  private static Optional<String> location(final JavaClass c) {
    return c.getSource().map(s -> s.getUri().toString().split("!")[0]);
  }

  /**
   * The module directory name ({@code guards}, {@code core}...).
   *
   * @param c the class
   * @return the directory name, or empty if the class is not from a module build output
   */
  public static Optional<String> nameOf(final JavaClass c) {
    return location(c)
        .filter(u -> u.contains("/build/"))
        .map(u -> u.substring(0, u.indexOf("/build/")))
        .map(u -> u.substring(u.lastIndexOf('/') + 1));
  }

  /**
   * True when the class comes from the module directory.
   *
   * @param dir the module directory with slashes around it, for example {@code /core/}
   * @return the predicate
   */
  public static DescribedPredicate<JavaClass> inModule(final String dir) {
    return DescribedPredicate.describe(
        "in module " + dir,
        c -> location(c).filter(u -> u.contains(dir) && u.contains("/build/")).isPresent());
  }
}
