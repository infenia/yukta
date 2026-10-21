// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * Who may call the REST API and the MCP endpoint. The filters ask this interface, so a module can
 * replace the default (API keys, {@link ApiKeyAuthenticator}) by providing another bean; the
 * default steps aside when one exists. What a caller may do is the {@link ApiKeys.Match} it
 * returns: a role, and the sessions it is limited to.
 */
public interface ApiAuthenticator {

  /** The two surfaces that need a caller. */
  enum Scope {
    /** {@code /api/**} and the actuator. */
    REST,
    /** The MCP endpoint. */
    MCP
  }

  /**
   * Whether the surface needs no caller at all (nothing is configured).
   *
   * @param scope the surface
   * @return true if every request is let through
   */
  boolean isOpen(Scope scope);

  /**
   * Whether control commands are recorded under the caller's name ({@code key:<name>}) instead of
   * no actor.
   *
   * @return true if the name of the match is the audit actor
   */
  boolean named();

  /**
   * Identify the caller of a request.
   *
   * @param scope the surface
   * @param headers the request headers
   * @return what the caller may do, or null if it is not recognised
   */
  ApiKeys.@Nullable Match authenticate(Scope scope, HttpHeaders headers);
}
