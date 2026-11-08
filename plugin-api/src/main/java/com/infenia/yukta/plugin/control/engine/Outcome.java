// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

/** What became of one directive against one target. */
public enum Outcome {

  /** Done. */
  APPLIED,

  /** The target does not exist. */
  NOT_FOUND,

  /** The supervisor does not own the target. */
  NOT_OWNED,

  /** The precondition ({@code ifStatus}) did not match. */
  PRECONDITION_FAILED,

  /** Nothing to do: the target is already in the wanted state. */
  ALREADY_IN_STATE
}
