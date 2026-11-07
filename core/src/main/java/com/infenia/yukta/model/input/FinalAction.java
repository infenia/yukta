// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.input;

/** What happens when the last tier of a route gives up. */
public enum FinalAction {
  /** The request expires and the asking node fails. */
  FAIL,
  /** The request is answered with its default decision, or expires if it has none. */
  USE_DEFAULT
}
