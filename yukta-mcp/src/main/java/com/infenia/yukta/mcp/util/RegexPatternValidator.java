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

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.experimental.UtilityClass;

/**
 * Centralized regex pattern validation utility. Eliminates duplication of pattern validation logic
 * across the MCP tools.
 */
@UtilityClass
public class RegexPatternValidator {

  /**
   * Validate and compile a regex pattern.
   *
   * @param pattern the regex pattern to validate
   * @throws IllegalArgumentException if pattern is invalid
   */
  public static void validatePattern(final String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return;
    }
    try {
      Pattern.compile(pattern);
    } catch (final PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage(), e);
    }
  }

  /**
   * Check if text matches the given regex pattern.
   *
   * @param text the text to match
   * @param pattern the regex pattern
   * @return true if matches or pattern is null/blank, false otherwise
   * @throws IllegalArgumentException if pattern is invalid
   */
  @SuppressWarnings("PMD.OnlyOneReturn")
  public static boolean matches(final String text, final String pattern) {
    final boolean isBlankPattern = pattern == null || pattern.isBlank();
    if (isBlankPattern) {
      return true;
    }
    try {
      return Pattern.compile(pattern).matcher(text).find();
    } catch (final PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage(), e);
    }
  }
}
