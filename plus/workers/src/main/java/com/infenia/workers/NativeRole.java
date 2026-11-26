// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import lombok.experimental.UtilityClass;

/**
 * Checks that a native image was built as the workers role its settings ask for. Spring chooses the
 * property-conditioned beans of a native image when it is built, so an image built without {@code
 * -Pyukta.workers.native} would start with worker settings and silently never take or run a node.
 * On the JVM the beans are chosen at start, so the settings and the beans always agree.
 */
@UtilityClass
public class NativeRole {

  /**
   * Fails when the image cannot serve its settings.
   *
   * @param nativeImage whether this is a native image
   * @param controlPlane {@code yukta.workers.control-plane}, empty when not set
   * @param token {@code yukta.workers.token}, empty when not set
   * @param hasAgent whether the image has the worker's agent
   * @param hasExecutor whether the image has the control plane's executor
   * @throws IllegalStateException if it was built as another role or as none
   */
  public static void check(
      final boolean nativeImage,
      final String controlPlane,
      final String token,
      final boolean hasAgent,
      final boolean hasExecutor) {
    final String problem = nativeImage ? problem(controlPlane, token, hasAgent, hasExecutor) : null;
    if (problem != null) {
      throw new IllegalStateException(problem);
    }
  }

  /** Why an image with these beans cannot serve these settings, or null when it can. */
  private static String problem(
      final String controlPlane,
      final String token,
      final boolean hasAgent,
      final boolean hasExecutor) {
    String why = null;
    if (controlPlane.isEmpty()) {
      if (!token.isEmpty() && !hasExecutor) {
        why = message("yukta.workers.token", "a control plane", "control-plane");
      }
    } else if (!hasAgent) {
      why = message("yukta.workers.control-plane", "a worker", "worker");
    }
    return why;
  }

  private static String message(final String setting, final String role, final String flag) {
    return setting
        + " is set, but this native image was not built as "
        + role
        + ", so it would never take or run a node. Build it with -Pyukta.workers=true"
        + " -Pyukta.workers.native="
        + flag;
  }
}
