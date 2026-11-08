// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

/**
 * A reviewer that asks every supervisor whose scope matches, in order, as a chain of parents will
 * once delegation arrives. It exercises the answer pipeline (refusals, needs for approval,
 * timeouts, dry run) without the ownership rule; production asks the owner only.
 */
public final class PipelineReviewer extends ControlReviewer {

  private final ObjectProvider<ExecutionSupervisor> beans;

  /**
   * Creates the reviewer.
   *
   * @param beans the supervisors to ask
   * @param timeout longest a supervisor may take to answer
   */
  public PipelineReviewer(final ObjectProvider<ExecutionSupervisor> beans, final Duration timeout) {
    super(beans, timeout);
    this.beans = beans;
  }

  @Override
  public Mono<ReviewVerdict> verdict(final ControlRequest request, final Set<String> satisfied) {
    return Mono.deferContextual(
        context -> {
          final boolean byPerson =
              context.getOrDefault(ControlActor.KIND_KEY, null) != ControlActor.Kind.SUPERVISOR;
          final List<ExecutionSupervisor> asked =
              Stream.concat(beans.stream(), attachedSupervisors())
                  .filter(
                      supervisor ->
                          supervisor
                              .scope()
                              .matches(request.sessionId(), request.workflowId(), request.nodeId()))
                  .toList();
          return ask(asked, request, satisfied, byPerson);
        });
  }
}
