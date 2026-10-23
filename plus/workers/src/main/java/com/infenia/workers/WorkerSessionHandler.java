// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.worker.WorkerInfo;
import com.infenia.yukta.service.worker.WorkerRegistration;
import com.infenia.yukta.service.worker.WorkerRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.rsocket.RSocketRequester;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

/**
 * The control plane's side of one worker connection. The first frame must be a {@code REGISTER}
 * with the shared token; every {@code LEASE} after it renews the worker's lease (and is refused
 * once this server is no longer the leader, so the worker moves on to the next control plane); when
 * the stream ends the worker is removed. Anything else is refused with a {@code REJECT} and the
 * stream ends, so the worker reconnects and registers again.
 */
@Slf4j
public class WorkerSessionHandler {

  /** The workers that are connected. */
  private final WorkerRegistry registry;

  /** The way back into each connected worker. */
  private final WorkerConnections connections;

  /** Tells whether this server is the leader; only the leader takes workers. */
  private final LeaderElection election;

  /** The shared secret workers must present. */
  private final byte[] token;

  /**
   * Creates the handler.
   *
   * @param registry the workers that are connected
   * @param connections the way back into each connected worker
   * @param election tells whether this server is the leader; a standby refuses workers
   * @param token the shared secret workers must present
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the registry is a shared Spring bean
  public WorkerSessionHandler(
      final WorkerRegistry registry,
      final WorkerConnections connections,
      final LeaderElection election,
      final String token) {
    this.registry = registry;
    this.connections = connections;
    this.election = election;
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Serves one worker connection.
   *
   * @param requester the way back into this worker
   * @param frames what the worker sends
   * @return what the control plane answers
   */
  public Flux<WorkerFrame> session(
      final RSocketRequester requester, final Flux<WorkerFrame> frames) {
    return frames.switchOnFirst((first, all) -> serve(requester, first, all));
  }

  private Flux<WorkerFrame> serve(
      final RSocketRequester requester,
      final Signal<? extends WorkerFrame> first,
      final Flux<WorkerFrame> all) {
    final WorkerFrame hello = first.get();
    final String refusal = refusal(hello);
    Flux<WorkerFrame> result = Flux.just(WorkerFrame.reject(refusal == null ? "" : refusal));
    if (refusal == null) {
      try {
        result = accept(requester, registry.register(registration(hello)), all);
      } catch (final IllegalArgumentException e) {
        result = Flux.just(WorkerFrame.reject(e.getMessage()));
      }
    }
    return result;
  }

  /** Why the first frame is not a good registration, or null if it is. */
  private String refusal(final WorkerFrame hello) {
    return isRegistration(hello) ? admissionProblem(hello) : "the first frame must be REGISTER";
  }

  private static boolean isRegistration(final WorkerFrame hello) {
    return hello != null && hello.type() == WorkerFrame.Type.REGISTER;
  }

  /** Why this server will not take the worker, or null if it will. */
  private String admissionProblem(final WorkerFrame hello) {
    String why = null;
    if (!election.isLeader()) {
      why = "this server is not the leader";
    }
    if (why == null && !tokenMatches(hello.token())) {
      log.atWarn().addKeyValue("workerId", hello.workerId()).log("Worker refused: bad token");
      why = "token refused";
    }
    return why;
  }

  private static WorkerRegistration registration(final WorkerFrame hello) {
    return new WorkerRegistration(
        hello.workerId(), hello.pools(), hello.pluginTypes(), hello.capacity());
  }

  private Flux<WorkerFrame> accept(
      final RSocketRequester requester, final WorkerInfo info, final Flux<WorkerFrame> all) {
    connections.put(info.workerId(), requester);
    log.atInfo().addKeyValue("workerId", info.workerId()).log("Worker registered");
    return Flux.concat(Flux.just(WorkerFrame.ack()), all.skip(1).map(frame -> answer(info, frame)))
        .takeUntil(frame -> frame.type() == WorkerFrame.Type.REJECT)
        .doFinally(
            _ -> {
              connections.remove(info.workerId(), requester);
              registry.remove(info.workerId(), info.registeredAt());
              log.atInfo().addKeyValue("workerId", info.workerId()).log("Worker gone");
            });
  }

  private WorkerFrame answer(final WorkerInfo info, final WorkerFrame frame) {
    WorkerFrame answer = WorkerFrame.reject("unexpected " + frame.type() + " frame");
    if (frame.type() == WorkerFrame.Type.LEASE) {
      // a leader that lost the database lease must not keep its workers: the new leader has none
      if (election.isLeader()) {
        answer =
            registry.heartbeat(info.workerId(), frame.usedSlots(), frame.draining())
                ? WorkerFrame.ack()
                : WorkerFrame.reject("lease expired, register again");
      } else {
        answer = WorkerFrame.reject("this server is not the leader");
      }
    }
    return answer;
  }

  private boolean tokenMatches(final String presented) {
    return presented != null
        && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
  }
}
