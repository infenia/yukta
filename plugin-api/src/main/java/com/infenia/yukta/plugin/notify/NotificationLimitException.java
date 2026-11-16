// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import java.io.Serial;

/**
 * A run raises too many notifications. The message names the limit that was hit.
 *
 * @since 0.1.0-alpha.5
 */
public final class NotificationLimitException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which limit, and for which run
   */
  public NotificationLimitException(final String message) {
    super(message);
  }
}
