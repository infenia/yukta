// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Evaluates the {@code if} conditions of rules.
 *
 * <p>A condition sees one map of fields and can only read from it: no method calls, no types, no
 * assignment.
 */
@UtilityClass
public class Conditions {

  /** Parses conditions; thread safe. */
  private static final SpelExpressionParser PARSER = new SpelExpressionParser();

  /**
   * Parses a condition so that mistakes surface when the rules are loaded.
   *
   * @param ruleId the rule, for the error message
   * @param condition the SpEL text
   * @return the expression
   * @throws IllegalArgumentException if the text is not valid SpEL
   */
  public static Expression parse(final String ruleId, final String condition) {
    try {
      return PARSER.parseExpression(condition);
    } catch (final org.springframework.expression.ParseException e) {
      throw new IllegalArgumentException(
          "Rule '" + ruleId + "' has an invalid condition: " + e.getMessage(), e);
    }
  }

  /**
   * Evaluates a condition.
   *
   * @param expression the condition
   * @param fields what the condition can read
   * @return true if it holds
   * @throws org.springframework.expression.EvaluationException if it cannot be evaluated
   */
  public static boolean holds(final Expression expression, final Map<String, Object> fields) {
    final EvaluationContext context =
        SimpleEvaluationContext.forPropertyAccessors(new MapAccessor()).build();
    return Boolean.TRUE.equals(expression.getValue(context, fields, Boolean.class));
  }
}
