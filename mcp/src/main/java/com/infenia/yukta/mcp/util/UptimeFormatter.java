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
package com.infenia.yukta.mcp.util;

import lombok.experimental.UtilityClass;

/**
 * Formats system uptime into human-readable strings. Testable in isolation with no external
 * dependencies.
 */
@UtilityClass
@SuppressWarnings("PMD.OnlyOneReturn")
public class UptimeFormatter {

  /**
   * Format milliseconds into a human-readable uptime string.
   *
   * @param uptimeMs the uptime in milliseconds
   * @return formatted uptime string (e.g., "2d 3h 45m")
   */
  public static String format(final long uptimeMs) {
    final long seconds = uptimeMs / 1000;
    final long minutes = seconds / 60;
    final long hours = minutes / 60;
    final long days = hours / 24;

    if (days > 0) {
      return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m";
    } else if (hours > 0) {
      return hours + "h " + (minutes % 60) + "m";
    } else if (minutes > 0) {
      return minutes + "m " + (seconds % 60) + "s";
    } else {
      return seconds + "s";
    }
  }

  /**
   * Get current system uptime as a formatted string.
   *
   * @return formatted system uptime
   */
  public static String getSystemUptime() {
    final long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
    return format(uptimeMs);
  }
}
