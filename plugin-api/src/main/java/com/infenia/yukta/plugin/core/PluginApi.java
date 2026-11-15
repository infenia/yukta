// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Version of the plugin SPI ({@code plugin-api}), as {@code MAJOR.MINOR}. A minor release only adds
 * (a new default method, a new optional field), so a plugin built against {@code 1.0} runs on a
 * {@code 1.3} server; a major release may break plugins. A plugin built outside this repository
 * reports what it was built against by overriding {@link Plugin#apiVersion()}.
 */
@UtilityClass
public class PluginApi {

  /** Major version: a change here can break plugins. */
  public static final int MAJOR = 1;

  /** Minor version: additions only. */
  public static final int MINOR = 0;

  /** {@code MAJOR.MINOR}; a compile-time constant, so a plugin that returns it keeps its own. */
  public static final String VERSION = MAJOR + "." + MINOR;

  private static final Pattern FORMAT = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})");

  /**
   * Why a plugin built against {@code version} cannot run on this server.
   *
   * @param version the SPI version the plugin reports; null means not declared (a test double)
   * @return the reason, or null when it is compatible
   */
  public static @Nullable String refusal(final @Nullable String version) {
    if (version == null) {
      return null;
    }
    final Matcher m = FORMAT.matcher(version);
    if (!m.matches()) {
      return "SPI version '" + version + "' is not MAJOR.MINOR";
    }
    if (Integer.parseInt(m.group(1)) == MAJOR) {
      return Integer.parseInt(m.group(2)) > MINOR
          ? "built for SPI " + version + ", newer than this server's SPI " + VERSION
          : null;
    }
    return "built for SPI " + version + ", this server has SPI " + VERSION;
  }
}
