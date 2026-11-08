// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** What a supervisor passes up to its parents. */
public enum DelegationKind {

  /** One of its own commands needs approval. */
  APPROVAL,

  /** It escalates an input request. */
  INPUT,

  /** Its engine asks a question. */
  CONSULT
}
