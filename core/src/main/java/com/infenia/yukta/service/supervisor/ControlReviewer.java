// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Asks every {@link ExecutionSupervisor} whether a control command may run. */
@Slf4j
@Component
public class ControlReviewer {

  /** The registered supervisors; looked up per review so late registrations count. */
  private final ObjectProvider<ExecutionSupervisor> supervisors;

  /** The supervisors attached at run time; set by Spring, empty without it. */
  private AttachedSupervisors attached = new AttachedSupervisors();

  /** Longest a single supervisor may take to answer; a hung review must not block a run. */
  private final Duration timeout;

  /**
   * Create the reviewer.
   *
   * @param supervisors the registered supervisors
   * @param timeout longest a supervisor may take to answer ({@code
   *     yukta.supervisor.review-timeout})
   */
  @Autowired
  public ControlReviewer(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      @Value("${yukta.supervisor.review-timeout:5s}") final Duration timeout) {
    this.supervisors = supervisors;
    this.timeout = timeout;
  }

  /**
   * Create the reviewer with the default 5 s time limit.
   *
   * @param supervisors the registered supervisors
   */
  public ControlReviewer(final ObjectProvider<ExecutionSupervisor> supervisors) {
    this(supervisors, Duration.ofSeconds(5));
  }

  /**
   * Includes the supervisors attached at run time in reviews.
   *
   * @param attachedSupervisors the set
   */
  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects the shared service, which is meant to be held")
  public void setAttachedSupervisors(final AttachedSupervisors attachedSupervisors) {
    this.attached = attachedSupervisors;
  }

  /**
   * Reviews a command in supervisor order, stopping at the first refusal.
   *
   * @param request the command and who sent it
   * @return completes if every supervisor allows it; fails with {@link ControlRejectedException}
   *     otherwise. A supervisor that fails to answer counts as a refusal, and so does one that
   *     takes longer than {@code yukta.supervisor.review-timeout} (default 5 s), except for a stop:
   *     a stop is allowed when the review times out, so a hung supervisor cannot keep a person from
   *     stopping a run.
   */
  public Mono<Void> review(final ControlRequest request) {
    return Flux.fromStream(
            () ->
                Stream.concat(supervisors.stream(), attached.stream())
                    .filter(
                        supervisor ->
                            supervisor.scope().matches(request.sessionId(), request.workflowId()))
                    .sorted(Comparator.comparingInt(ExecutionSupervisor::order)))
        .concatMap(
            supervisor ->
                Mono.defer(() -> supervisor.review(request))
                    .timeout(
                        timeout,
                        Mono.fromSupplier(() -> fallback(supervisor, request, "review timed out")))
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .setCause(error)
                              .addKeyValue("supervisor", supervisor.id())
                              .log("Supervisor review failed");
                          return Mono.just(Decision.deny("review failed"));
                        })
                    .filter(decision -> !decision.allowed())
                    .map(decision -> supervisor.id() + ": " + decision.reason()))
        .next()
        .flatMap(reason -> Mono.<Void>error(new ControlRejectedException(reason)));
  }

  /** The answer when a supervisor times out: a stop is allowed, anything else refused. */
  private Decision fallback(
      final ExecutionSupervisor supervisor, final ControlRequest request, final String why) {
    final boolean stop =
        request.command() instanceof ExecutionControlCommand.StopWorkflowCommand
            || request.command() instanceof ExecutionControlCommand.StopNodeCommand;
    log.atWarn()
        .addKeyValue("supervisor", supervisor.id())
        .addKeyValue("timeout", timeout)
        .log(
            stop ? "Supervisor: {}; allowing the stop" : "Supervisor: {}; refusing the command",
            why);
    return stop ? Decision.ALLOW : Decision.deny(why);
  }
}
