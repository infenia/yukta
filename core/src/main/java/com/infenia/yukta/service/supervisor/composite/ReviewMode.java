// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

/** How the reviews of the children of a composite supervisor add up. */
public enum ReviewMode {
  /** Every child must allow; the first denial wins. */
  ALL,
  /** One allowing child is enough. */
  ANY,
  /** More than half of the children must allow. */
  MAJORITY
}
