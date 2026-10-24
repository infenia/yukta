// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Applies a control command to a whole tree of runs: a run and every run it started as a
 * sub-workflow. Pausing a parent alone holds its own nodes, while a child it waits on runs on; with
 * the tree, the command reaches the child too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TreeControl {

  /** Statuses of a run that has not ended yet. */
  private static final Set<String> UNFINISHED = Set.of("PENDING", "QUEUED", "RUNNING", "PAUSED");

  /** Knows the runs of a tree. */
  private final TaskTrackerService tracker;

  /**
   * Applies a command to a run, and with {@code tree} also to every unfinished run below it. The
   * run itself goes first and its failure is the result; a run below that ends meanwhile, or
   * refuses the command, is skipped.
   *
   * @param executionId the run at the top of the tree
   * @param tree whether the runs below it get the command too
   * @param command the command for one run
   * @return completes when every run got the command
   */
  public Mono<Void> apply(
      final String executionId, final boolean tree, final Function<String, Mono<?>> command) {
    final Mono<Void> top = Mono.defer(() -> command.apply(executionId)).then();
    return tree
        ? top.then(
            Flux.defer(() -> Flux.fromIterable(below(executionId)))
                .concatMap(
                    child ->
                        Mono.defer(() -> command.apply(child))
                            .then()
                            .onErrorResume(
                                e -> {
                                  log.atDebug()
                                      .addKeyValue("executionId", child)
                                      .addKeyValue("error", e.getMessage())
                                      .log("A run of the tree did not take the command");
                                  return Mono.empty();
                                }))
                .then())
        : top;
  }

  private List<String> below(final String executionId) {
    return tracker.getTree(executionId).stream()
        .filter(run -> !run.executionId().equals(executionId))
        .filter(run -> UNFINISHED.contains(run.status()))
        .map(WorkflowExecutionSummary::executionId)
        .toList();
  }
}
