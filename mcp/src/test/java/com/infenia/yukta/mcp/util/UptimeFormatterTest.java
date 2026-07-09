// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UptimeFormatterTest {

  @Test
  void testFormatDays() {
    String result = UptimeFormatter.format(2 * 24 * 60 * 60 * 1000L + 3 * 60 * 60 * 1000L);
    assertTrue(result.contains("d"));
    assertTrue(result.contains("h"));
  }

  @Test
  void testFormatHours() {
    String result = UptimeFormatter.format(3 * 60 * 60 * 1000L + 45 * 60 * 1000L);
    assertTrue(result.contains("h"));
    assertTrue(result.contains("m"));
  }

  @Test
  void testFormatMinutes() {
    String result = UptimeFormatter.format(45 * 60 * 1000L + 30 * 1000L);
    assertTrue(result.contains("m"));
    assertTrue(result.contains("s"));
  }

  @Test
  void testFormatSeconds() {
    String result = UptimeFormatter.format(30 * 1000L);
    assertTrue(result.contains("s"));
  }

  @Test
  void testGetSystemUptime() {
    String uptime = UptimeFormatter.getSystemUptime();
    assertNotNull(uptime);
    assertTrue(
        uptime.contains("d")
            || uptime.contains("h")
            || uptime.contains("m")
            || uptime.contains("s"));
  }
}
