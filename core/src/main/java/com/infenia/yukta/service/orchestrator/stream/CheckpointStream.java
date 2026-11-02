// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Adds the checkpoint writes and the completion marker to a node's message stream. */
@Slf4j
@UtilityClass
class CheckpointStream {

  /** Most messages written to the checkpoint store in one call. */
  private static final int CHECKPOINT_BATCH_SIZE = 64;

  /** Longest, in milliseconds, a message waits for its batch to fill. */
  private static final long CHECKPOINT_BATCH_WAIT_MILLIS = 20;

  /**
   * Applies the checkpoint policy to a stream.
   *
   * @param store the checkpoint store
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param stream the incoming message stream
   * @param checkpoint what the node writes to the store
   * @return the stream with the checkpoint operators added
   */
  /* default */ static Flux<Message<?>> apply(
      final NodeCheckpointStore store,
      final String executionId,
      final String nodeId,
      final Flux<Message<?>> stream,
      final NodePolicy.Checkpoint checkpoint) {
    Flux<Message<?>> logStream = stream;
    // A store that cannot save a message must not fail the run, the same as one that cannot write
    // the end marker. The node is then left unmarked, so a resume runs it again instead of
    // trusting a partial output.
    final AtomicBoolean saveFailed = new AtomicBoolean();
    switch (checkpoint) {
      case NONE -> saveFailed.set(true); // never marked complete: a resume runs the node again
      case LAST -> {
        final AtomicReference<Message<?>> last = new AtomicReference<>();
        logStream =
            logStream
                .doOnNext(last::set)
                .concatWith(
                    Mono.defer(
                            () ->
                                last.get() == null
                                    ? Mono.<Void>empty()
                                    : tolerate(
                                        store.save(executionId, nodeId, last.get()),
                                        executionId,
                                        nodeId,
                                        saveFailed))
                        .then(Mono.empty()));
      }
      default ->
          // One write per batch instead of per message. A batch waits at most
          // CHECKPOINT_BATCH_WAIT, so a quiet node still passes its messages on.
          // ponytail: still on the stream thread; give it its own scheduler if stores stay slow.
          logStream =
              logStream
                  .bufferTimeout(
                      CHECKPOINT_BATCH_SIZE, Duration.ofMillis(CHECKPOINT_BATCH_WAIT_MILLIS), true)
                  .concatMap(
                      batch ->
                          tolerate(
                                  store.saveAll(executionId, nodeId, batch),
                                  executionId,
                                  nodeId,
                                  saveFailed)
                              .thenMany(Flux.fromIterable(batch)));
    }
    // Only a node that completed normally is marked, and its completion goes on downstream once the
    // marker is durable. A store that cannot write it must not fail the run.
    logStream =
        logStream.concatWith(
            Mono.defer(
                    () ->
                        saveFailed.get()
                            ? Mono.<Void>empty()
                            : store.markComplete(executionId, nodeId))
                .onErrorResume(
                    error -> {
                      log.atError()
                          .addKeyValue("executionId", executionId)
                          .addKeyValue("nodeId", nodeId)
                          .setCause(error)
                          .log("Could not mark the node as complete");
                      return Mono.empty();
                    })
                .then(Mono.empty()));
    return logStream;
  }

  /**
   * Turns a failed checkpoint write into a logged, marked failure so the run goes on.
   *
   * @param save the checkpoint write
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param saveFailed set when the write fails
   * @return the write, completing empty on failure
   */
  private static Mono<Void> tolerate(
      final Mono<Void> save,
      final String executionId,
      final String nodeId,
      final AtomicBoolean saveFailed) {
    return save.onErrorResume(
        error -> {
          saveFailed.set(true);
          log.atError()
              .addKeyValue("executionId", executionId)
              .addKeyValue("nodeId", nodeId)
              .setCause(error)
              .log("Could not save a checkpoint");
          return Mono.empty();
        });
  }
}
