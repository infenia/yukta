// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package com.infenia.yukta.controller;

/** Canary: matches the yukta-spel-standard-evaluation-context OpenGrep rule and has no tests. */
public final class CanaryOpengrep {
  static final class StandardEvaluationContext {}

  /** Creates the flagged object. */
  public Object create(boolean flag) {
    if (flag) {
      return new StandardEvaluationContext();
    }
    return null;
  }
}
