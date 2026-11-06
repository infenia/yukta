// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** Who made an attachment, which decides who may change it. */
public enum SupervisorSource {
  /** Made over the API or the UI; changed by hand. */
  MANUAL,
  /** Declared in a session or workflow definition; owned by the reconciler. */
  DEFINITION,
  /** Made by a bean or a property; read-only. */
  SYSTEM
}
