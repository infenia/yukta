// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** Groups the decision makers in the catalog, by where the decision is made. */
public enum SupervisorCategory {
  /** Rules written in the supervisor's settings decide. */
  RULES,
  /** A system outside the server decides, over HTTP. */
  EXTERNAL_SYSTEM,
  /** Something running on this server decides: a program or a workflow. */
  ON_THIS_SERVER
}
