// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * What an API key may do. {@code VIEWER} reads; {@code OPERATOR} also starts, controls and recovers
 * executions; {@code ADMIN} may do everything, including defining sessions and workflows (which can
 * run processes, scripts and SQL) and changing scheduler limits.
 */
public enum ApiRole {
  /** Read-only. */
  VIEWER,
  /** Read, plus start and control executions. */
  OPERATOR,
  /** Everything. */
  ADMIN;

  /** Mutating paths an operator may call. */
  private static final List<String> OPERATOR_PATHS = List.of("/api/executions/", "/api/workflow/");

  /**
   * Whether this role may make the request.
   *
   * @param method the HTTP method
   * @param path the request path
   * @return true if allowed
   */
  public boolean allows(final HttpMethod method, final String path) {
    final boolean read =
        HttpMethod.GET.equals(method)
            || HttpMethod.HEAD.equals(method)
            || HttpMethod.OPTIONS.equals(method);
    // no ".." segments: a prefix match must not be a way out of the prefix
    return this == ADMIN
        || read
        || (this == OPERATOR
            && !path.contains("..")
            && OPERATOR_PATHS.stream().anyMatch(path::startsWith));
  }
}
