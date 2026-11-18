// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import com.infenia.yukta.security.ApiRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The tool table of {@code docs/reference/mcp-tools.mdx}: each tool and the role it needs. */
final class McpDocs {

  private static final Path TABLE = Path.of("..", "docs", "reference", "mcp-tools.mdx");
  private static final Pattern ROW =
      Pattern.compile("^\\| `(\\w+)` \\| (VIEWER|OPERATOR|ADMIN) \\|", Pattern.MULTILINE);

  private McpDocs() {}

  /**
   * The tools of the document.
   *
   * @return the lowest role of each tool, by name
   * @throws IOException if the document cannot be read
   */
  /* default */ static Map<String, ApiRole> roles() throws IOException {
    final Map<String, ApiRole> roles = new java.util.concurrent.ConcurrentHashMap<>();
    final Matcher row = ROW.matcher(Files.readString(TABLE));
    while (row.find()) {
      roles.put(row.group(1), ApiRole.valueOf(row.group(2)));
    }
    return roles;
  }
}
