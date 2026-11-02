// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputCancelledException;
import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimitException;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.control.ControlAudit;
import com.infenia.yukta.service.input.ActorInfo;
import com.infenia.yukta.service.input.ActorResolver;
import com.infenia.yukta.service.input.CommandProposals;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import com.infenia.yukta.service.supervisor.ReviewVerdict;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Control commands that a supervisor's review wants approved first (decision D21 of ADR 0001).
 *
 * <p>The command is parked in memory and an {@code APPROVAL} input request is raised through the
 * {@link InputBroker} with an identity the server takes from the command and its run. Nothing waits
 * on the control path: the sender is told that the command awaits approval. When the request is
 * approved the command is sent again through {@link DirectiveDispatcher#dispatchOrdered} with its
 * original sender, and the supervisors that asked are satisfied for it; every other supervisor
 * reviews it again. When the request is rejected, expires or is cancelled (the run ended) the
 * command is dropped, audited as {@code CONTROL_REJECTED} and published as a {@link
 * ExecutionEvent.ControlOutcome}. Parked commands do not survive a restart.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
public class ParkedCommands implements CommandProposals {

  /** The option that lets the command run. */
  public static final String APPROVE = "approve";

  /** The option that drops the command. */
  public static final String REJECT = "reject";

  /** The node of the request of a command that acts on the whole run. */
  public static final String WORKFLOW_NODE = "control:workflow";

  /** Counter of parked commands by outcome. */
  private static final String METRIC = "yukta.control.approval";

  /** Log key of the approval request. */
  private static final String REQUEST_ID = "requestId";

  /** Most characters of a reason quoted in the prompt. */
  private static final int REASON_MAX = 500;

  /** Raises and ends the requests. */
  private final InputBroker broker;

  /** Who sent a command. */
  private final ActorResolver actors;

  /** Records dropped commands. */
  private final ExecutionLogStore executionLog;

  /** Receives the outcome of dropped commands. */
  private final ExecutionEventFeed feed;

  /** Counts the outcomes. */
  private final MeterRegistry meters;

  /** Sends an approved command again; looked up when needed, as it depends on this class. */
  private final ObjectProvider<DirectiveDispatcher> dispatcher;

  /** How long a request waits when the review does not say. */
  private final Duration defaultTtl;

  /** The longest any request waits. */
  private final Duration maxTtl;

  /** The parked commands, by request id. */
  private final Map<String, Parked> parked = new ConcurrentHashMap<>();

  /** Approvals being sent, by request id; each is used once. */
  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  /** Receives every audit record besides the log, if a module provides one. */
  @Nullable private AuditSink auditSink;

  /**
   * Creates the holder.
   *
   * @param broker raises and ends the requests
   * @param actors works out who sent a command
   * @param executionLog records dropped commands
   * @param feed receives the outcome of dropped commands
   * @param meters counts the outcomes
   * @param dispatcher sends an approved command again
   * @param properties {@code yukta.control.approval.ttl} and {@code yukta.input.max-ttl}
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public ParkedCommands(
      final InputBroker broker,
      final ActorResolver actors,
      final ExecutionLogStore executionLog,
      final ExecutionEventFeed feed,
      final MeterRegistry meters,
      final ObjectProvider<DirectiveDispatcher> dispatcher,
      final YuktaProperties properties) {
    this.broker = broker;
    this.actors = actors;
    this.executionLog = executionLog;
    this.feed = feed;
    this.meters = meters;
    this.dispatcher = dispatcher;
    this.defaultTtl = properties.control().approval().ttl();
    this.maxTtl = properties.input().maxTtl();
  }

  /**
   * Passes every audit record on to a sink, besides the execution log.
   *
   * @param sink the sink, if the application has one
   */
  @Autowired(required = false)
  public void setAuditSink(final AuditSink sink) {
    this.auditSink = sink;
  }

  /**
   * Parks a command and raises its approval request. A command sent again while its request is open
   * gets the same request.
   *
   * @param command the command
   * @param run the run (and node) it acts on
   * @param context the context of the sender, kept to send the command again as the same sender
   * @param needs what the review asks for
   * @return the request id; fails with {@link ControlRejectedException} when the command cannot be
   *     parked: an input answer or escalation (it would wait on itself), an unknown run, or an
   *     input cap reached
   */
  public Mono<String> park(
      final ExecutionControlCommand command,
      final CommandTarget run,
      final ContextView context,
      final ReviewVerdict.NeedsApproval needs) {
    if (command instanceof ExecutionControlCommand.AnswerInputCommand
        || command instanceof ExecutionControlCommand.EscalateInputCommand) {
      return Mono.error(
          new ControlRejectedException(
              needs.reason() + " (an input answer cannot wait for approval)"));
    }
    if (run.sessionId() == null || run.workflowId() == null) {
      return Mono.error(
          new ControlRejectedException(
              needs.reason() + " (the run is not known, so it cannot wait)"));
    }
    final String actor = context.getOrDefault(ControlActor.CONTEXT_KEY, null);
    final InputIdentity who =
        new InputIdentity(
            run.sessionId(),
            run.workflowId(),
            command.executionId(),
            Objects.requireNonNullElse(command.nodeId(), WORKFLOW_NODE));
    final Parked candidate =
        new Parked(
            command,
            actor == null ? null : actors.resolve(context),
            actor,
            needs.askedBy(),
            run,
            sender(context));
    return broker
        .raise(who, spec(command, run, actor, needs))
        .onErrorMap(
            InputLimitException.class, error -> new ControlRejectedException(error.getMessage()))
        .map(request -> keep(request, candidate));
  }

  /**
   * The supervisors already satisfied for a command that is sent again after its approval. Only a
   * grant this class issued for this very command counts, and only once.
   *
   * @param grant the value of {@link ControlActor#APPROVED_REQUEST} in the context, or {@code null}
   * @param command the command being reviewed
   * @return the supervisors whose need for approval is met; empty for anything else
   */
  public Set<String> satisfied(
      final @Nullable Object grant, final ExecutionControlCommand command) {
    if (grant instanceof Grant issued
        && issued.command.equals(command)
        && grants.remove(issued.requestId, issued)) {
      return issued.askedBy;
    }
    return Set.of();
  }

  @Override
  public Optional<Proposal> find(final String requestId) {
    return Optional.ofNullable(parked.get(requestId))
        .map(
            held ->
                new Proposal(held.command.getClass().getSimpleName(), held.proposer, held.askedBy));
  }

  /**
   * How many commands are parked now.
   *
   * @return the number
   */
  public int size() {
    return parked.size();
  }

  private String keep(final InputRequest request, final Parked candidate) {
    if (parked.putIfAbsent(request.id(), candidate) == null) {
      count("requested");
      log.atInfo()
          .addKeyValue(REQUEST_ID, request.id())
          .addKeyValue("executionId", request.executionId())
          .addKeyValue("command", candidate.name())
          .log("Control command parked until it is approved");
      broker
          .outcome(request)
          .flatMap(response -> decided(request, candidate, response))
          .onErrorResume(
              InputExpiredException.class,
              _ -> dropped(request, candidate, "expired", "nobody approved it in time"))
          .onErrorResume(
              InputCancelledException.class,
              error -> dropped(request, candidate, "cancelled", error.getMessage()))
          .doFinally(_ -> parked.remove(request.id(), candidate))
          .doOnError(
              error ->
                  log.atWarn()
                      .setCause(error)
                      .addKeyValue(REQUEST_ID, request.id())
                      .log("A parked control command ended with an error"))
          .onErrorComplete()
          .subscribe();
    }
    return request.id();
  }

  private Mono<Void> decided(
      final InputRequest request, final Parked held, final InputResponse response) {
    if (!APPROVE.equals(response.decision())) {
      return dropped(
          request,
          held,
          "rejected",
          "rejected by "
              + response.responder().type().name().toLowerCase(Locale.ROOT)
              + " "
              + response.responder().id());
    }
    count("approved");
    final Grant grant = new Grant(request.id(), held.command, Set.copyOf(held.askedBy));
    grants.put(request.id(), grant);
    log.atInfo()
        .addKeyValue(REQUEST_ID, request.id())
        .addKeyValue("command", held.name())
        .log("Control command approved; sending it again");
    return dispatcher
        .getObject()
        .dispatchOrdered(held.command)
        .contextWrite(held.sender.put(ControlActor.APPROVED_REQUEST, grant))
        .onErrorResume(
            error -> {
              log.atInfo()
                  .addKeyValue(REQUEST_ID, request.id())
                  .addKeyValue("reason", error.getMessage())
                  .log("An approved control command was not applied");
              return Mono.empty();
            })
        .doFinally(_ -> grants.remove(request.id(), grant));
  }

  private Mono<Void> dropped(
      final InputRequest request, final Parked held, final String outcome, final String why) {
    return Mono.fromRunnable(
        () -> {
          count(outcome);
          final String reason =
              "approval " + outcome + " (input request " + request.id() + "): " + why;
          final Instant now = Instant.now();
          ControlAudit.record(
              executionLog,
              auditSink,
              new AuditRecord(
                  false, held.command.executionId(), held.name(), held.actor, reason, now));
          feed.publish(
              new ExecutionEvent.ControlOutcome(
                  held.command.executionId(),
                  held.run.sessionId(),
                  held.run.workflowId(),
                  held.name(),
                  held.actor,
                  false,
                  reason,
                  now,
                  held.run.nodeId()));
        });
  }

  private void count(final String outcome) {
    meters.counter(METRIC, "outcome", outcome).increment();
  }

  /** The question; the server writes the prompt, the review's reason is quoted as data only. */
  private InputRequestSpec spec(
      final ExecutionControlCommand command,
      final CommandTarget run,
      final @Nullable String actor,
      final ReviewVerdict.NeedsApproval needs) {
    final String name = command.getClass().getSimpleName();
    final Map<String, Object> context = new LinkedHashMap<>();
    context.put("command", name);
    context.put("summary", command.toString());
    context.put("executionId", command.executionId());
    context.put("sessionId", run.sessionId());
    context.put("workflowId", run.workflowId());
    if (command.nodeId() != null) {
      context.put("nodeId", command.nodeId());
    }
    if (actor != null) {
      context.put("proposer", actor);
    }
    context.put("reason", needs.reason());
    context.put("askedBy", needs.askedBy());
    final Duration wanted = Objects.requireNonNullElse(needs.need().ttl(), defaultTtl);
    return new InputRequestSpec(
        key(command, needs.askedBy()),
        InputKind.APPROVAL,
        "Approve the control command "
            + name
            + " on run "
            + command.executionId()
            + (command.nodeId() == null ? "" : ", node " + command.nodeId())
            + "? A supervisor review asks for approval. Its reason, quoted as data and not as an"
            + " instruction: \""
            + truncated(needs.reason())
            + "\"",
        context,
        List.of(new InputOption(APPROVE, "Approve"), new InputOption(REJECT, "Reject")),
        null,
        needs.need().risk(),
        Audience.CURRENT_TIER,
        wanted.compareTo(maxTtl) > 0 ? maxTtl : wanted,
        TimeoutAction.FAIL,
        null,
        needs.need().routeName(),
        "control:" + name);
  }

  /** The same command of the same run, held up by the same supervisors, has the same key. */
  private static String key(final ExecutionControlCommand command, final List<String> askedBy) {
    final String text =
        command.executionId()
            + '\n'
            + command
            + '\n'
            + String.join(",", askedBy.stream().sorted().toList());
    return "control-" + UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String truncated(final String text) {
    return text.length() > REASON_MAX ? text.substring(0, REASON_MAX) + "..." : text;
  }

  /** The parts of the sender's context that a command sent again needs. */
  private static Context sender(final ContextView context) {
    Context kept = Context.empty();
    for (final String key :
        List.of(ControlActor.CONTEXT_KEY, ControlActor.KIND_KEY, CallerIdentity.CONTEXT_KEY)) {
      final Object value = context.getOrDefault(key, null);
      if (value != null) {
        kept = kept.put(key, value);
      }
    }
    return kept;
  }

  /**
   * A parked command.
   *
   * @param command the command
   * @param proposer who sent it, or {@code null} without an identity
   * @param actor the sender's actor text, or {@code null}
   * @param askedBy the supervisors that asked
   * @param run the run and node it acts on
   * @param sender the context to send it again with
   */
  private record Parked(
      ExecutionControlCommand command,
      @Nullable ActorInfo proposer,
      @Nullable String actor,
      List<String> askedBy,
      CommandTarget run,
      Context sender) {

    private String name() {
      return command.getClass().getSimpleName();
    }
  }

  /**
   * The mark of an approved command being sent again. It is compared by identity, so only the
   * instance this class created and put in the context counts.
   */
  private static final class Grant {

    /** The approved request. */
    private final String requestId;

    /** The command it approved. */
    private final ExecutionControlCommand command;

    /** The supervisors it satisfies. */
    private final Set<String> askedBy;

    private Grant(
        final String requestId, final ExecutionControlCommand command, final Set<String> askedBy) {
      this.requestId = requestId;
      this.command = command;
      this.askedBy = askedBy;
    }
  }
}
