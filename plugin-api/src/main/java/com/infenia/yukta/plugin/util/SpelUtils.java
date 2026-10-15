// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import reactor.core.publisher.Mono;

/** Utility for SpEL expression evaluation and caching. */
@UtilityClass
public final class SpelUtils {
  /** Which methods an expression may call. */
  private static final SafeMethodResolver SAFE_METHODS = new SafeMethodResolver();

  /** SpEL expression parser instance. */
  private static final ExpressionParser PARSER = new SpelExpressionParser();

  /** Maximum number of parsed expressions kept. */
  /* default */ static final int MAX_CACHED = 1000;

  /** Cache for parsed SpEL expressions, least recently used evicted. */
  private static final Map<String, Expression> CACHE =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Serial private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Expression> eldest) {
              return size() > MAX_CACHED;
            }
          });

  /* default */ static int cacheSize() {
    return CACHE.size();
  }

  /**
   * Evaluate a SpEL expression against a message.
   *
   * @param expressionStr the expression string
   * @param root the root object
   * @param variables additional variables
   * @param <T> the result type
   * @return a Mono emitting the result
   */
  public static <T> Mono<T> evaluate(
      final String expressionStr, final Object root, final Map<String, Object> variables) {
    return Mono.fromCallable(() -> evaluateSync(expressionStr, root, variables));
  }

  /**
   * Synchronously evaluate a SpEL expression against a root object in a sandboxed context.
   *
   * @param expressionStr the expression string
   * @param root the root object
   * @param <T> the result type
   * @return the evaluation result
   */
  // Type witness is inferred from the assignment context, matching the same trade-off as
  // Collections.emptyList(); a Class<T> witness parameter would break every plugin call site.
  @SuppressWarnings("TypeParameterUnusedInFormals")
  public static <T> T evaluateSync(final String expressionStr, final Object root) {
    return evaluateSync(expressionStr, root, null);
  }

  /**
   * Synchronously evaluate a SpEL expression against a root object with variables.
   *
   * @param expressionStr the expression string
   * @param root the root object
   * @param variables the variables
   * @param <T> the result type
   * @return the evaluation result
   */
  @SuppressWarnings({
    "unchecked",
    "TypeParameterUnusedInFormals",
    "PMD.AvoidCatchingGenericException"
  })
  public static <T> T evaluateSync(
      final String expressionStr, final Object root, final Map<String, Object> variables) {
    final Expression expression = CACHE.computeIfAbsent(expressionStr, PARSER::parseExpression);
    // Read-only property access and a short list of methods only: no T(...), constructors or bean
    // refs.
    final SimpleEvaluationContext context =
        SimpleEvaluationContext.forPropertyAccessors(
                SpelBudget.guard(new MapAccessor()),
                SpelBudget.guard(DataBindingPropertyAccessor.forReadOnlyAccess()))
            .withRootObject(root)
            .withMethodResolvers(SAFE_METHODS)
            .build();
    if (variables != null) {
      variables.forEach(context::setVariable);
    }
    try {
      return (T) SpelBudget.run(() -> expression.getValue(context));
    } catch (RuntimeException e) {
      if (SpelBudget.exceeded(e)) {
        throw new WorkflowExecutionException(
            "Expression evaluation exceeded its time budget of "
                + SpelBudget.timeoutMillis()
                + " ms (-D"
                + SpelBudget.TIMEOUT_PROPERTY
                + ")",
            e);
      }
      throw e;
    }
  }

  /**
   * Parse an expression and cache it.
   *
   * @param expressionStr the expression string
   */
  public static void preParse(final String expressionStr) {
    if (expressionStr != null && !expressionStr.isBlank()) {
      CACHE.computeIfAbsent(expressionStr, PARSER::parseExpression);
    }
  }
}
