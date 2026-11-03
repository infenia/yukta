// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.session.InternalSessions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * A supervisor that is a workflow of an internal session. For each event of a listed type it starts
 * the workflow with the (redacted) event and maps what the workflow ends with to commands: {@code
 * SUCCESS} with {@code {"commands": [...]}}, {@code ESCALATE} as an escalation of the request in
 * the event, {@code RETRY} once more, anything else as no opinion.
 *
 * <p>It is a {@link ControlPlanePlugin}, so it also works as a child of a {@code CHAIN} composite.
 */
@Slf4j
@SuppressWarnings({
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidUsingVolatile",
  "PMD.AvoidLiteralsInIfCondition",
  "PMD.AvoidSynchronizedStatement"
})
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shared Spring beans, held on purpose")
public final class WorkflowSupervisor implements ControlPlanePlugin {

  /** How many event keys are remembered to drop a redelivered event. */
  private static final int SEEN_MAX = 1000;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The most commands taken from one run. */
  private static final int MAX_COMMANDS = 10;

  private final String profileId;

  private final WorkflowSettings settings;

  private final SupervisorWorkflowRunner runner;

  private final InternalSessions internal;

  private final InputRedactor redactor;

  /** Commands the profile names; {@code null} for no restriction. */
  private final @Nullable Set<String> allowed;

  /** Limits the runs of this supervisor at once. */
  private final Semaphore concurrent;

  /** Recent event keys, oldest first. */
  private final Map<String, Boolean> seen =
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
          return size() > SEEN_MAX;
        }
      };

  /** The binding id, which is the actor of the commands; set when the binding starts. */
  private volatile String bindingId;

  /**
   * Creates the supervisor.
   *
   * @param profileId the profile id (the binding id until a binding is set)
   * @param settings the settings
   * @param runner starts the runs
   * @param internal tells whether the session is internal
   * @param redactor masks secrets in the event
   * @param allowed the commands the profile names
   */
  public WorkflowSupervisor(
      final String profileId,
      final WorkflowSettings settings,
      final SupervisorWorkflowRunner runner,
      final InternalSessions internal,
      final InputRedactor redactor,
      final @Nullable Set<String> allowed) {
    this.profileId = profileId;
    this.bindingId = profileId;
    this.settings = settings;
    this.runner = runner;
    this.internal = internal;
    this.redactor = redactor;
    this.allowed = allowed;
    this.concurrent = new Semaphore(settings.maxConcurrent());
  }

  /**
   * Tells the supervisor under which id its binding sends commands.
   *
   * @param id the binding id
   */
  public void bindTo(final String id) {
    this.bindingId = id;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return profileId;
  }

  @Override
  public Duration decisionTimeout() {
    return settings.timeout().plusSeconds(5);
  }

  @Override
  public Mono<ControlDecision> decide(final ExecutionEvent event) {
    if (!settings.events().contains(event.getClass().getSimpleName()) || !fresh(event)) {
      return Mono.empty();
    }
    return internal
        .checkInternal(settings.sessionId())
        .filter(Boolean::booleanValue)
        .switchIfEmpty(
            Mono.fromRunnable(
                () ->
                    log.atWarn()
                        .addKeyValue("supervisor", bindingId)
                        .log("Session of the supervisor workflow is not internal; no opinion")))
        .filter(Boolean::booleanValue)
        .filter(_ -> concurrent.tryAcquire())
        .flatMap(_ -> attempt(event, 0).doFinally(_ -> concurrent.release()));
  }

  private Mono<ControlDecision> attempt(final ExecutionEvent event, final int attempt) {
    return runner
        .run(
            bindingId,
            settings.sessionId(),
            settings.workflowId(),
            payload(event),
            event.executionId(),
            settings.timeout())
        .flatMap(
            result ->
                "RETRY".equals(result.outcome()) && attempt == 0
                    ? attempt(event, 1)
                    : Mono.justOrEmpty(decision(event, result)));
  }

  private @Nullable ControlDecision decision(final ExecutionEvent event, final RunResult result) {
    if (!"SUCCESS".equals(result.status())) {
      return null;
    }
    final java.util.List<ExecutionControlCommand> commands;
    if ("ESCALATE".equals(result.outcome())) {
      commands = escalation(event, result);
    } else if (result.outcome() == null || "SUCCESS".equals(result.outcome())) {
      final SupervisorResultMapper.Mapped mapped =
          SupervisorResultMapper.map(result.results(), allowed, event.executionId(), MAX_COMMANDS);
      if (mapped.dropped() > 0) {
        log.atWarn()
            .addKeyValue("supervisor", bindingId)
            .addKeyValue("dropped", mapped.dropped())
            .log("Dropped commands the supervisor workflow may not send");
      }
      commands = mapped.commands();
    } else {
      commands = java.util.List.of();
    }
    return commands.isEmpty()
        ? null
        : new ControlDecision(commands, 1, ControlDecision.Source.RULE);
  }

  private static java.util.List<ExecutionControlCommand> escalation(
      final ExecutionEvent event, final RunResult result) {
    final String requestId =
        switch (event) {
          case ExecutionEvent.InputRequested requested -> requested.request().id();
          case ExecutionEvent.InputEscalated escalated -> escalated.request().id();
          default -> null;
        };
    final String reason = result.reason() == null ? "escalated by a workflow" : result.reason();
    return requestId == null
        ? java.util.List.of()
        : java.util.List.of(
            new ExecutionControlCommand.EscalateInputCommand(
                event.executionId(), requestId, "workflow-escalated", reason));
  }

  private Map<String, Object> payload(final ExecutionEvent event) {
    final Map<String, Object> asMap =
        JSON.convertValue(event, new tools.jackson.core.type.TypeReference<>() {});
    final Map<String, Object> redacted = redactor.map(asMap);
    final Map<String, Object> out = new java.util.HashMap<>();
    out.put("event", redacted);
    out.put("request", redacted.get("request"));
    out.put("supervisorId", bindingId);
    out.put("bindingId", bindingId);
    return out;
  }

  /** True the first time an event is seen: a redelivered event does not start a second run. */
  private boolean fresh(final ExecutionEvent event) {
    final String key = bindingId + ":" + event.getClass().getSimpleName() + ":" + event;
    synchronized (seen) {
      return seen.put(key, Boolean.TRUE) == null;
    }
  }
}
