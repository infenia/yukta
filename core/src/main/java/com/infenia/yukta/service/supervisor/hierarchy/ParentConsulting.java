// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.hierarchy;

import com.infenia.yukta.plugin.control.Delegation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Asks the parents of a supervisor a question (delegation kind {@code CONSULT}) in the mode set for
 * it. Unlike an approval a question has no allow or deny: each parent that has an opinion gives a
 * typed answer, and a parent that is silent, declines or is too slow is left out.
 *
 * <ul>
 *   <li>{@code ORDERED}: the parents in order; the first answer is the result.
 *   <li>{@code ANY}: all at once; the first answer to arrive is the result.
 *   <li>{@code ALL}: all at once; the result holds every answer given.
 * </ul>
 *
 * <p>When no parent answers the result is empty; the asking engine decides what that means for it.
 */
@SuppressWarnings({"PMD.UseConcurrentHashMap"})
@UtilityClass
public class ParentConsulting {

  /**
   * Asks the parents.
   *
   * @param how the mode and the time one parent has
   * @param parents the parent supervisor ids, in order
   * @param ask one parent's typed answer, empty when it has none
   * @return the answers by parent id, empty when none answered
   */
  public static Mono<Map<String, Map<String, Object>>> consult(
      final Delegation how,
      final List<String> parents,
      final Function<String, Mono<Map<String, Object>>> ask) {
    return switch (how.mode()) {
      case ORDERED ->
          Flux.fromIterable(parents)
              .concatMap(parent -> one(how, parent, ask))
              .next()
              .map(ParentConsulting::single)
              .defaultIfEmpty(Map.of());
      case ANY ->
          Flux.fromIterable(parents)
              .flatMap(parent -> one(how, parent, ask))
              .next()
              .map(ParentConsulting::single)
              .defaultIfEmpty(Map.of());
      case ALL ->
          Flux.fromIterable(parents)
              .flatMap(parent -> one(how, parent, ask))
              .collectList()
              .map(ParentConsulting::merged);
    };
  }

  private static Mono<Map.Entry<String, Map<String, Object>>> one(
      final Delegation how,
      final String parent,
      final Function<String, Mono<Map<String, Object>>> ask) {
    return Mono.defer(() -> ask.apply(parent))
        .timeout(how.timeout(), Mono.empty())
        .onErrorResume(_ -> Mono.empty())
        .filter(answer -> !answer.isEmpty())
        .map(answer -> Map.entry(parent, answer));
  }

  private static Map<String, Map<String, Object>> single(
      final Map.Entry<String, Map<String, Object>> answer) {
    return Map.of(answer.getKey(), answer.getValue());
  }

  private static Map<String, Map<String, Object>> merged(
      final List<Map.Entry<String, Map<String, Object>>> answers) {
    final Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    answers.forEach(answer -> out.put(answer.getKey(), answer.getValue()));
    return Map.copyOf(out);
  }
}
