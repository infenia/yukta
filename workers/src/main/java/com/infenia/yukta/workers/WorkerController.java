// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

/** The RSocket route workers connect to. */
@Controller
public class WorkerController {

  /** Serves the connections. */
  private final WorkerSessionHandler handler;

  /**
   * Creates the controller.
   *
   * @param handler serves the connections
   */
  public WorkerController(final WorkerSessionHandler handler) {
    this.handler = handler;
  }

  /**
   * One worker connection: the worker registers, then renews its lease.
   *
   * @param requester the way back into this worker
   * @param frames what the worker sends
   * @return what the control plane answers
   */
  @MessageMapping("worker.session")
  public Flux<WorkerFrame> session(
      final RSocketRequester requester, final Flux<WorkerFrame> frames) {
    return handler.session(requester, frames);
  }
}
