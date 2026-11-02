// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Builds the assemblers of a restarted or resumed run for the nodes that do not run again: a node
 * that feeds the re-run part replays what it recorded, any other node stays silent. Both go through
 * the same control and broadcast steps as a node that runs, so the graph is built one way.
 */
@Component
@RequiredArgsConstructor
public class ReplayNodeAssembler {

  private final TaskTrackerService tracker;
  private final StreamTopologyDecorator streamTopologyDecorator;

  /**
   * Replays the recorded output of a node, reported {@code RUNNING} then {@code SUCCESS}.
   *
   * @param node the node
   * @param index its position in topological order
   * @param bufferSize prefetch of the shared stream
   * @param recorded what the node emitted in the previous run
   * @param previousExecutionId the run it was recorded in
   * @return the assembler
   */
  public NodeAssembler replay(
      final WorkflowNode node,
      final int index,
      final int bufferSize,
      final List<Message<?>> recorded,
      final String previousExecutionId) {
    return context -> {
      final String executionId = context.executionId();
      final Flux<Message<?>> source =
          Flux.fromIterable(recorded)
              .doOnSubscribe(_ -> emit(executionId, node, TaskStatus.RUNNING, Map.of()))
              .doOnComplete(
                  () ->
                      emit(
                          executionId,
                          node,
                          TaskStatus.SUCCESS,
                          Map.of("replayedFrom", previousExecutionId)))
              .doOnCancel(() -> emit(executionId, node, TaskStatus.CANCELLED, Map.of()));
      context.streams()[index] = decorated(node, bufferSize, source, context);
    };
  }

  /**
   * A node outside the re-run part that nothing replays: it emits nothing and ends {@code SKIPPED}
   * (it did not run).
   *
   * @param node the node
   * @param index its position in topological order
   * @param bufferSize prefetch of the shared stream
   * @return the assembler
   */
  public NodeAssembler silent(final WorkflowNode node, final int index, final int bufferSize) {
    return context -> context.streams()[index] = decorated(node, bufferSize, Flux.empty(), context);
  }

  // The checkpoint is not written again: the orchestrator records the replayed input itself.
  private Flux<Message<?>> decorated(
      final WorkflowNode node,
      final int bufferSize,
      final Flux<Message<?>> source,
      final AssemblyContext context) {
    return streamTopologyDecorator.applyLoggingAndBroadcasting(
        context.executionId(),
        node.nodeId(),
        context.control().applyPostProcessingControls(node.nodeId(), source),
        bufferSize,
        context.disposables(),
        context.connectors(),
        () -> false,
        NodePolicy.Checkpoint.NONE);
  }

  private void emit(
      final String executionId,
      final WorkflowNode node,
      final TaskStatus status,
      final Map<String, Object> metadata) {
    tracker.emitTaskStatusEvent(executionId, node.nodeId(), node.type(), status, metadata);
  }
}
