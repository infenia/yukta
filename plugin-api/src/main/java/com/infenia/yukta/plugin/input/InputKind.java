// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

/**
 * What an input request asks for, and so what an answer looks like.
 *
 * @since 0.1.0-alpha.4
 */
public enum InputKind {

  /** A yes or no, or any short decision such as {@code approve} or {@code reject}. */
  APPROVAL,

  /** One of the listed options. */
  CHOICE,

  /** Free text. */
  TEXT,

  /** Data that matches the request's JSON Schema. */
  STRUCTURED,

  /** A question about the state of the system; the answer may carry data. */
  QUERY
}
