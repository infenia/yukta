// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** How several parents are asked. A refusal from any parent asked is final in every mode. */
public enum DelegationMode {

  /** Ask in order; the first answer decides; a silent parent passes it on. */
  ORDERED,

  /** Ask all at once; all must allow. */
  ALL,

  /** Ask all at once; the first answer decides. */
  ANY
}
