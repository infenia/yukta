// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.input.InputRequest;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/**
 * What an engine may read: the executions and open input requests of what its supervisor owns,
 * newest first and bounded. It never returns node outputs or message payloads.
 */
public interface EngineReads {

  /** Reads nothing; what an engine gets before the supervisor is attached. */
  EngineReads NONE =
      new EngineReads() {
        @Override
        public Flux<RunView> executions(final @Nullable String status, final int limit) {
          return Flux.empty();
        }

        @Override
        public Flux<InputRequest> openInputs(final int limit) {
          return Flux.empty();
        }
      };

  /**
   * One execution.
   *
   * @param sessionId its session
   * @param executionId its id
   * @param workflowId its workflow
   * @param status its status name
   * @param startTime when it started, or {@code null}
   */
  record RunView(
      String sessionId,
      String executionId,
      String workflowId,
      String status,
      @Nullable Instant startTime) {}

  /**
   * The owned executions.
   *
   * @param status only this status (a {@code TaskStatus} name), or {@code null} for all
   * @param limit most rows, at most 200
   * @return the executions
   */
  Flux<RunView> executions(@Nullable String status, int limit);

  /**
   * The open and escalated input requests of what is owned.
   *
   * @param limit most rows, at most 200
   * @return the requests
   */
  Flux<InputRequest> openInputs(int limit);

  /**
   * The executions this supervisor paused and has not resumed or stopped (its hold ledger).
   *
   * @return their ids; none by default
   */
  default Flux<String> held() {
    return Flux.empty();
  }
}
