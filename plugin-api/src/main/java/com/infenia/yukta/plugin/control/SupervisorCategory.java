// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** Groups supervisor types in the catalog. */
public enum SupervisorCategory {
  /** Stops or pauses a run when something goes wrong. */
  GUARD,
  /** Holds a decision for a person to approve. */
  GATE,
  /** Decides by rules. */
  RULES,
  /** Decides with a model. */
  MODEL,
  /** Talks to a system outside the server. */
  INTEGRATION,
  /** Combines other supervisors. */
  COMPOSITION,
  /** Is a workflow of an internal session. */
  WORKFLOW
}
