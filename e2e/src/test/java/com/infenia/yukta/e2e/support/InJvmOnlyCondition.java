// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Evaluates {@link InJvmOnly}. */
@NoArgsConstructor
final class InJvmOnlyCondition implements ExecutionCondition {

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
    return YuktaServer.external()
        ? ConditionEvaluationResult.disabled(
            "needs the in-JVM server, not " + YuktaServer.baseUrl())
        : ConditionEvaluationResult.enabled("in-JVM server");
  }
}
