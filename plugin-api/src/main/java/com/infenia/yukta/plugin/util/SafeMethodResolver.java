// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.support.DataBindingMethodResolver;

/**
 * Lets an expression call only a short list of cheap, side-effect free methods (text, number,
 * collection and {@code java.time} basics). Anything else, such as {@code repeat}, {@code getClass}
 * or a method of a payload object, is not found, so one expression cannot allocate without bound or
 * reach the JVM.
 */
final class SafeMethodResolver implements MethodResolver {

  /** Methods allowed on any receiver. */
  private static final Set<String> ALLOWED =
      Set.of(
          "equals",
          "hashCode",
          "toString",
          "compareTo",
          "length",
          "size",
          "isEmpty",
          "isBlank",
          "contains",
          "containsKey",
          "containsValue",
          "get",
          "getOrDefault",
          "indexOf",
          "lastIndexOf",
          "charAt",
          "startsWith",
          "endsWith",
          "equalsIgnoreCase",
          "matches",
          "substring",
          "trim",
          "strip",
          "toUpperCase",
          "toLowerCase",
          "replace",
          "split",
          "keySet",
          "values",
          "isPresent",
          "orElse",
          "intValue",
          "longValue",
          "doubleValue",
          "booleanValue",
          "name",
          "ordinal",
          "abs",
          "max",
          "min");

  /** Packages whose types are all value types without side effects. */
  private static final List<String> VALUE_PACKAGES = List.of("java.time.");

  private final MethodResolver delegate = DataBindingMethodResolver.forInstanceMethodInvocation();

  @Override
  public @Nullable MethodExecutor resolve(
      final EvaluationContext context,
      final Object target,
      final String name,
      final List<TypeDescriptor> argumentTypes)
      throws AccessException {
    final String type = target.getClass().getName();
    if (ALLOWED.contains(name) || VALUE_PACKAGES.stream().anyMatch(type::startsWith)) {
      return delegate.resolve(context, target, name, argumentTypes);
    }
    return null;
  }
}
