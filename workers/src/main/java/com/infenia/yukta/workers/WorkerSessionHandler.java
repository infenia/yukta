// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import com.infenia.yukta.service.worker.WorkerInfo;
import com.infenia.yukta.service.worker.WorkerRegistration;
import com.infenia.yukta.service.worker.WorkerRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

/**
 * The control plane's side of one worker connection. The first frame must be a {@code REGISTER}
 * with the shared token; every {@code LEASE} after it renews the worker's lease; when the stream
 * ends the worker is removed. Anything else is refused with a {@code REJECT} and the stream ends,
 * so the worker reconnects and registers again.
 */
@Slf4j
public class WorkerSessionHandler {

  /** The workers that are connected. */
  private final WorkerRegistry registry;

  /** The shared secret workers must present. */
  private final byte[] token;

  /**
   * Creates the handler.
   *
   * @param registry the workers that are connected
   * @param token the shared secret workers must present
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the registry is a shared Spring bean
  public WorkerSessionHandler(final WorkerRegistry registry, final String token) {
    this.registry = registry;
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Serves one worker connection.
   *
   * @param frames what the worker sends
   * @return what the control plane answers
   */
  public Flux<WorkerFrame> session(final Flux<WorkerFrame> frames) {
    return frames.switchOnFirst(this::serve);
  }

  private Flux<WorkerFrame> serve(
      final Signal<? extends WorkerFrame> first, final Flux<WorkerFrame> all) {
    final WorkerFrame hello = first.get();
    final String refusal = refusal(hello);
    Flux<WorkerFrame> result = Flux.just(WorkerFrame.reject(refusal == null ? "" : refusal));
    if (refusal == null) {
      try {
        result = accept(registry.register(registration(hello)), all);
      } catch (final IllegalArgumentException e) {
        result = Flux.just(WorkerFrame.reject(e.getMessage()));
      }
    }
    return result;
  }

  /** Why the first frame is not a good registration, or null if it is. */
  private String refusal(final WorkerFrame hello) {
    String why = null;
    if (hello == null || hello.type() != WorkerFrame.Type.REGISTER) {
      why = "the first frame must be REGISTER";
    } else if (!tokenMatches(hello.token())) {
      log.atWarn().addKeyValue("workerId", hello.workerId()).log("Worker refused: bad token");
      why = "token refused";
    }
    return why;
  }

  private static WorkerRegistration registration(final WorkerFrame hello) {
    return new WorkerRegistration(
        hello.workerId(), hello.pools(), hello.pluginTypes(), hello.capacity());
  }

  private Flux<WorkerFrame> accept(final WorkerInfo info, final Flux<WorkerFrame> all) {
    log.atInfo().addKeyValue("workerId", info.workerId()).log("Worker registered");
    return Flux.concat(Flux.just(WorkerFrame.ack()), all.skip(1).map(frame -> answer(info, frame)))
        .takeUntil(frame -> frame.type() == WorkerFrame.Type.REJECT)
        .doFinally(
            _ -> {
              registry.remove(info.workerId(), info.registeredAt());
              log.atInfo().addKeyValue("workerId", info.workerId()).log("Worker gone");
            });
  }

  private WorkerFrame answer(final WorkerInfo info, final WorkerFrame frame) {
    final boolean renewed =
        frame.type() == WorkerFrame.Type.LEASE
            && registry.heartbeat(info.workerId(), frame.usedSlots());
    WorkerFrame answer = WorkerFrame.ack();
    if (frame.type() != WorkerFrame.Type.LEASE) {
      answer = WorkerFrame.reject("unexpected " + frame.type() + " frame");
    } else if (!renewed) {
      answer = WorkerFrame.reject("lease expired, register again");
    }
    return answer;
  }

  private boolean tokenMatches(final String presented) {
    return presented != null
        && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
  }
}
