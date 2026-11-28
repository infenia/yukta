// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.model.workflow.WorkflowAdmission;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowKey;
import com.infenia.yukta.plugin.util.VariableResolver;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Works out what a run claims of its workflow's {@code admission} settings (its concurrency key and
 * throttle, by resolving their key expressions against the start payload in the usual expression
 * sandbox), and keeps the debounce windows of new starts.
 */
@Component
public class AdmissionKeys {

  /** Most debounce windows kept at once; past it the oldest end early. */
  private static final int MAX_DEBOUNCE_WINDOWS = 100_000;

  /** The key of a throttle or debounce without a key expression: the whole workflow. */
  private static final String WHOLE_WORKFLOW = "";

  /** The stored definitions, for restarts and resumes, which do not carry theirs. */
  private final WorkflowDefinitionStore definitions;

  /** Resolves the key expressions. */
  private final VariableResolver variables;

  /** The open debounce windows: the run that holds each, until its period ends. */
  private final Cache<DebounceKey, Window> windows;

  /**
   * Creates the keys.
   *
   * @param definitions the stored definitions
   * @param variables resolves the key expressions
   */
  @Autowired
  public AdmissionKeys(
      final WorkflowDefinitionStore definitions, final VariableResolver variables) {
    this.definitions = definitions;
    this.variables = variables;
    this.windows =
        Caffeine.newBuilder()
            .maximumSize(MAX_DEBOUNCE_WINDOWS)
            .expireAfter(Expiry.<DebounceKey, Window>creating((_, window) -> window.period()))
            .build();
  }

  /**
   * A start's debounce: the key its payload resolved to and the window.
   *
   * @param key the key
   * @param period how long after the first start later ones are folded into it
   */
  public record Debounce(String key, Duration period) {}

  /** A debounce key of one workflow. */
  private record DebounceKey(WorkflowKey workflow, String key) {}

  /** The run that holds a debounce window, and how long the window lasts. */
  private record Window(String executionId, Duration period) {}

  /**
   * What a run of a definition claims from the scheduler.
   *
   * @param definition the workflow
   * @param payload the run's start payload
   * @return the claims, or empty when the workflow has no concurrency key and no throttle
   * @throws IllegalArgumentException (as the Mono's error) if a key resolves to nothing
   */
  public Mono<ExecutionScheduler.Claims> claim(
      final WorkflowDefinition definition, final Map<String, Object> payload) {
    final WorkflowAdmission admission = definition.admission();
    if (admission == null || admission.concurrency() == null && admission.throttle() == null) {
      return Mono.empty();
    }
    final WorkflowAdmission.Concurrency concurrency = admission.concurrency();
    final WorkflowAdmission.Throttle throttle = admission.throttle();
    final Mono<Optional<ExecutionScheduler.KeyClaim>> byKey =
        concurrency == null
            ? Mono.just(Optional.empty())
            : resolve(concurrency.key(), payload, "admission.concurrency.key")
                .map(key ->
                    Optional.of(
                        new ExecutionScheduler.KeyClaim(
                            key, concurrency.limit(), concurrency.overflow())));
    final Mono<Optional<ExecutionScheduler.ThrottleClaim>> byRate =
        throttle == null
            ? Mono.just(Optional.empty())
            : keyOrWhole(throttle.key(), payload, "admission.throttle.key")
                .map(key ->
                    Optional.of(
                        new ExecutionScheduler.ThrottleClaim(
                            key, throttle.limit(), Duration.ofMillis(throttle.periodMs()))));
    return Mono.zip(byKey, byRate)
        .map(both ->
            new ExecutionScheduler.Claims(both.getT1().orElse(null), both.getT2().orElse(null)));
  }

  /**
   * The claims of a restart or resume, from the workflow's stored definition: the settings in force
   * now, not when the run first started.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param payload the run's start payload
   * @return the claims, or empty when there are none or the workflow is gone
   */
  public Mono<ExecutionScheduler.Claims> claim(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    return definitions.find(sessionId, workflowId).flatMap(def -> claim(def, payload));
  }

  /**
   * The debounce of a new start.
   *
   * @param definition the workflow
   * @param payload the start payload
   * @return the debounce, or empty when the workflow has none
   */
  public Mono<Debounce> debounce(
      final WorkflowDefinition definition, final Map<String, Object> payload) {
    final WorkflowAdmission admission = definition.admission();
    final WorkflowAdmission.Debounce debounce = admission == null ? null : admission.debounce();
    if (debounce == null) {
      return Mono.empty();
    }
    return keyOrWhole(debounce.key(), payload, "admission.debounce.key")
        .map(key -> new Debounce(key, Duration.ofMillis(debounce.periodMs())));
  }

  /**
   * Opens the debounce window for a start, or names the run that already holds it. Atomic: of two
   * starts at the same moment, one opens the window and the other gets its run.
   *
   * @param workflow the workflow
   * @param debounce the start's debounce
   * @param executionId the id the start would run under
   * @return {@code executionId} if this start opened the window and should run, otherwise the id of
   *     the run that holds it
   */
  public String joinOrOpen(
      final WorkflowKey workflow, final Debounce debounce, final String executionId) {
    return windows
        .asMap()
        .computeIfAbsent(
            new DebounceKey(workflow, debounce.key()),
            _ -> new Window(executionId, debounce.period()))
        .executionId();
  }

  /**
   * Closes a window a start opened but could not run under, so the next start runs instead of being
   * folded into a run that does not exist.
   *
   * @param workflow the workflow
   * @param debounce the start's debounce
   * @param executionId the id the start would have run under
   */
  public void close(final WorkflowKey workflow, final Debounce debounce, final String executionId) {
    windows
        .asMap()
        .computeIfPresent(
            new DebounceKey(workflow, debounce.key()),
            (_, window) -> window.executionId().equals(executionId) ? null : window);
  }

  /** A key expression's value, or the whole workflow's key when there is no expression. */
  private Mono<String> keyOrWhole(
      @Nullable final String expression, final Map<String, Object> payload, final String field) {
    return expression == null || expression.isBlank()
        ? Mono.just(WHOLE_WORKFLOW)
        : resolve(expression, payload, field);
  }

  /** A key expression's value; one that resolves to nothing refuses the start. */
  private Mono<String> resolve(
      final String expression, final Map<String, Object> payload, final String field) {
    return variables
        .resolve(expression, DefaultMessage.create(UUID.randomUUID(), payload))
        .map(String::valueOf)
        .filter(key -> !key.isBlank())
        .switchIfEmpty(
            Mono.error(
                new IllegalArgumentException(
                    field + " " + expression + " resolves to nothing for this start")));
  }
}
