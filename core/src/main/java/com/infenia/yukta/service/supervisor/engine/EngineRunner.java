// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.Outcome;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Runs the engine of one supervisor: signals go in, and every command that comes out passes the
 * {@link DirectiveGate} before it is applied. A command is applied at most once per idempotency
 * key; a repeat gets the first result. A failing engine resubscribes with backoff and never touches
 * a run. Directives that are not commands (verdicts, delegation answers, delegations, notes) go to
 * the {@code others} consumer.
 */
@Slf4j
public final class EngineRunner implements AutoCloseable {

  /** Where a command lands. */
  public record Target(
      @Nullable String sessionId, @Nullable String workflowId, @Nullable String nodeId) {}

  /**
   * What became of one command.
   *
   * @param key the idempotency key
   * @param outcome the outcome when it was applied, else {@code null}
   * @param rejection why the gate refused it, else {@code null}
   * @param recordOnly whether it was only recorded (dry run)
   */
  public record Result(
      String key,
      @Nullable Outcome outcome,
      DirectiveGate.@Nullable Rejection rejection,
      boolean recordOnly) {}

  /** Most keys remembered per runner. */
  private static final int REMEMBERED_KEYS = 1000;

  private final String supervisorId;
  private final SupervisorEngine engine;
  private final DirectiveGate gate;
  private final SupervisorCapabilities capabilities;
  private final SupervisorScope scope;
  private final boolean dryRun;
  private final Function<ExecutionControlCommand, Mono<Target>> targetOf;
  private final Function<ExecutionControlCommand, Mono<Outcome>> apply;
  private final Consumer<Result> results;
  private final Consumer<Directive> others;
  private final Map<String, Result> seen =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Result> eldest) {
              return size() > REMEMBERED_KEYS;
            }
          });

  /**
   * Creates the runner.
   *
   * @param supervisorId the supervisor
   * @param engine its decision maker
   * @param gate checks every command
   * @param capabilities what it may send
   * @param scope what it owns
   * @param dryRun whether its commands are only recorded
   * @param targetOf finds where a command lands, from the command itself
   * @param apply applies an accepted command and reports its outcome
   * @param results receives what became of every command, including the refused
   * @param others receives the directives that are not commands
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public EngineRunner(
      final String supervisorId,
      final SupervisorEngine engine,
      final DirectiveGate gate,
      final SupervisorCapabilities capabilities,
      final SupervisorScope scope,
      final boolean dryRun,
      final Function<ExecutionControlCommand, Mono<Target>> targetOf,
      final Function<ExecutionControlCommand, Mono<Outcome>> apply,
      final Consumer<Result> results,
      final Consumer<Directive> others) {
    this.supervisorId = supervisorId;
    this.engine = engine;
    this.gate = gate;
    this.capabilities = capabilities;
    this.scope = scope;
    this.dryRun = dryRun;
    this.targetOf = targetOf;
    this.apply = apply;
    this.results = results;
    this.others = others;
  }

  /**
   * Runs the engine on a stream of signals.
   *
   * @param signals what the supervisor owns and asks
   * @return completes when the signals end; errors of the engine are retried with backoff
   */
  public Mono<Void> run(final Flux<Signal> signals) {
    return Flux.defer(() -> engine.run(signals))
        .retryWhen(
            Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofMinutes(1)))
        .concatMap(this::handle)
        .then();
  }

  private Mono<Void> handle(final Directive directive) {
    if (!(directive instanceof final Directive.Command command)) {
      others.accept(directive);
      return Mono.empty();
    }
    final Result earlier = seen.get(command.key());
    if (earlier != null) {
      results.accept(earlier);
      return Mono.empty();
    }
    return targetOf
        .apply(command.command())
        .onErrorReturn(new Target(null, null, null))
        .flatMap(target -> gated(command, target))
        .doOnNext(
            result -> {
              seen.put(command.key(), result);
              results.accept(result);
            })
        .then();
  }

  private Mono<Result> gated(final Directive.Command command, final Target target) {
    final DirectiveGate.Decision decision =
        gate.check(
            supervisorId,
            command,
            target.sessionId(),
            target.workflowId(),
            target.nodeId(),
            capabilities,
            scope,
            true,
            dryRun);
    if (!decision.accepted()) {
      return Mono.just(
          new Result(
              command.key(), decision.outcome(), decision.rejection(), decision.recordOnly()));
    }
    return apply
        .apply(command.command())
        .map(outcome -> new Result(command.key(), outcome, null, false))
        .onErrorResume(
            error -> {
              log.atWarn()
                  .setCause(error)
                  .addKeyValue("supervisor", supervisorId)
                  .log("A command from an engine could not be applied");
              return Mono.just(new Result(command.key(), Outcome.NOT_FOUND, null, false));
            });
  }

  /** Closes the engine; called when the supervisor is detached. */
  @Override
  public void close() {
    engine.close();
  }
}
