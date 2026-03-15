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
