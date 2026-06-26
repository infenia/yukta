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
package com.infenia.yukta.util;

import java.util.Map;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

/** Utility class for evaluating Spring Expression Language (SpEL) expressions. */
@UtilityClass
public final class SpelUtils {
  /**
   * Evaluates a SpEL expression asynchronously.
   *
   * @param <T> the type of the expression result
   * @param expressionStr the SpEL expression string
   * @param root the root object for expression evaluation
   * @param variables a map of variables available in the expression context
   * @return a Mono containing the evaluated result
   */
  public static <T> Mono<T> evaluate(
      final String expressionStr, final Object root, final Map<String, Object> variables) {
    return com.infenia.yukta.message.util.SpelUtils.evaluate(expressionStr, root, variables);
  }

  /**
   * Evaluates a SpEL expression synchronously.
   *
   * @param <T> the type of the expression result
   * @param expressionStr the SpEL expression string
   * @param root the root object for expression evaluation
   * @return the evaluated result
   */
  public static <T> T evaluateSync(final String expressionStr, final Object root) {
    return com.infenia.yukta.message.util.SpelUtils.evaluateSync(expressionStr, root);
  }

  /**
   * Evaluates a SpEL expression synchronously with variables.
   *
   * @param <T> the type of the expression result
   * @param expressionStr the SpEL expression string
   * @param root the root object for expression evaluation
   * @param variables a map of variables available in the expression context
   * @return the evaluated result
   */
  public static <T> T evaluateSync(
      final String expressionStr, final Object root, final Map<String, Object> variables) {
    return com.infenia.yukta.message.util.SpelUtils.evaluateSync(expressionStr, root, variables);
  }

  /**
   * Pre-parses a SpEL expression to validate syntax.
   *
   * @param expressionStr the SpEL expression string to pre-parse
   */
  public static void preParse(final String expressionStr) {
    com.infenia.yukta.message.util.SpelUtils.preParse(expressionStr);
  }
}
