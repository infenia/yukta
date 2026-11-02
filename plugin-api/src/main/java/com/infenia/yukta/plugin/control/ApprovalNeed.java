// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Duration;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a review asks for when a command may run only once someone approves it: the server parks the
 * command and raises an {@code APPROVAL} input request with these settings.
 *
 * @param risk who may approve; {@code HIGH} (the default) is a human only
 * @param routeName the route of the request, or {@code null} for the default route
 * @param ttl how long the request waits, or {@code null} for {@code yukta.control.approval.ttl};
 *     never longer than {@code yukta.input.max-ttl}
 * @since 0.1.0
 */
public record ApprovalNeed(InputRisk risk, @Nullable String routeName, @Nullable Duration ttl) {

  /** The default: HIGH risk, the default route and the configured time to live. */
  public static final ApprovalNeed DEFAULT = new ApprovalNeed(InputRisk.HIGH, null, null);

  /** The form of a route name, as an input request takes it. */
  private static final Pattern ROUTE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  /**
   * Checks the settings.
   *
   * @param risk who may approve; {@code null} means {@code HIGH}
   * @param routeName the route, or {@code null}
   * @param ttl the time to live, or {@code null}
   * @throws IllegalArgumentException if the route name is not a valid name or the ttl is not
   *     positive
   */
  public ApprovalNeed {
    risk = risk == null ? InputRisk.HIGH : risk;
    if (routeName != null && !ROUTE_NAME.matcher(routeName).matches()) {
      throw new IllegalArgumentException("routeName must match [A-Za-z0-9._-]{1,64}");
    }
    if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
      throw new IllegalArgumentException("ttl must be positive");
    }
  }
}
