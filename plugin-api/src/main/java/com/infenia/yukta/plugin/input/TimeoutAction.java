// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

/**
 * What happens when a request is not answered before its deadline.
 *
 * @since 0.1.0-alpha.4
 */
public enum TimeoutAction {

  /** The ask fails with {@link InputExpiredException}. */
  FAIL,

  /** The ask completes with the request's default decision, answered by the system. */
  USE_DEFAULT,

  /** The request goes to the next tier of its route; after the last tier it fails. */
  ESCALATE
}
