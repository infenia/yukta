/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Utility for SpEL expression evaluation and caching. */
public final class SpelUtils {
  private static final ExpressionParser PARSER = new SpelExpressionParser();
  private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();
  private static final ThreadLocal<StandardEvaluationContext> CONTEXT_HOLDER =
      ThreadLocal.withInitial(
          () -> {
            final StandardEvaluationContext context = new StandardEvaluationContext();
            context.addPropertyAccessor(new MapAccessor());
            return context;
          });

  private SpelUtils() {
    // Utility class
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
    return Mono.fromCallable(
            () -> {
              final Expression expression =
                  CACHE.computeIfAbsent(expressionStr, PARSER::parseExpression);
              final StandardEvaluationContext context = new StandardEvaluationContext(root);
              if (variables != null) {
                context.setVariables(variables);
              }
              @SuppressWarnings("unchecked")
              final T result = (T) expression.getValue(context);
              return result;
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Synchronously evaluate a SpEL expression against a root object using a cached context.
   *
   * @param expressionStr the expression string
   * @param root the root object
   * @param <T> the result type
   * @return the evaluation result
   */
  @SuppressWarnings("unchecked")
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
  @SuppressWarnings("unchecked")
  public static <T> T evaluateSync(
      final String expressionStr, final Object root, final Map<String, Object> variables) {
    final Expression expression = CACHE.computeIfAbsent(expressionStr, PARSER::parseExpression);
    final StandardEvaluationContext context = CONTEXT_HOLDER.get();
    context.setRootObject(root);
    try {
      if (variables != null) {
        variables.forEach(context::setVariable);
      }
      return (T) expression.getValue(context);
    } finally {
      if (variables != null) {
        variables.keySet().forEach(key -> context.setVariable(key, null));
      }
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
