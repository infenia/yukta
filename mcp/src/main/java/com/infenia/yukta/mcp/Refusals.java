// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ControlledBySupervisorException;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

/**
 * Makes a refusal by a supervisor readable to an agent: the code, the supervisor that decided and
 * the reason, with a reminder that it is a decision and not an error to retry.
 */
@UtilityClass
public class Refusals {

  /**
   * Names a refusal in the error of a tool call.
   *
   * @param call the call
   * @param <T> what it returns
   * @return the call, with a supervisor's refusal described
   */
  public static <T> Mono<T> named(final Mono<T> call) {
    return call.onErrorMap(
            ControlledBySupervisorException.class,
            error ->
                new IllegalArgumentException(
                    ControlledBySupervisorException.CODE
                        + (error.getSupervisorId() == null
                            ? ""
                            : " (supervisor " + error.getSupervisorId() + ")")
                        + ": "
                        + error.getMessage()
                        + ". This is the decision of the owner, not an error to retry; tell the"
                        + " user.",
                    error))
        .onErrorMap(
            ControlRejectedException.class,
            error ->
                new IllegalArgumentException(
                    "CONTROL_REJECTED: "
                        + error.getMessage()
                        + ". A supervisor refused this; do not retry it, tell the user.",
                    error));
  }
}
