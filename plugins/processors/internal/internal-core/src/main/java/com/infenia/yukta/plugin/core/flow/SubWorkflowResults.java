// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** The message a SUB_WORKFLOW node emits for a finished child, and the port it routes it to. */
@UtilityClass
@SuppressWarnings("PMD.UseConcurrentHashMap")
class SubWorkflowResults {

  /** Port of a child that succeeded, or reached outcome SUCCESS. */
  /* default */ static final String PORT_SUCCESS = "success";

  /** Port of a child that failed, timed out, or reached outcome FAILED. */
  /* default */ static final String PORT_FAILURE = "failure";

  /** Port of a child that reached outcome RETRY. */
  /* default */ static final String PORT_RETRY = "retry";

  /** Port of a child that reached outcome ESCALATE. */
  /* default */ static final String PORT_ESCALATE = "escalate";

  /** Every port, in the order they are declared. */
  /* default */ static final List<String> PORTS =
      List.of(PORT_SUCCESS, PORT_FAILURE, PORT_RETRY, PORT_ESCALATE);

  /** How a child run that completed ended. */
  private static final String SUCCESS = "SUCCESS";

  /** The outcome that restarts work. */
  private static final String OUTCOME_RETRY = "RETRY";

  /** The outcome that hands the work to someone else. */
  private static final String OUTCOME_ESCALATE = "ESCALATE";

  /** The outcome that ends the work as failed. */
  private static final String OUTCOME_FAILED = "FAILED";

  /**
   * The result of a failed child, as a value to route instead of an error.
   *
   * @param failure the failure
   * @return its result: no results, the status it ended with and the failure as the reason
   */
  /* default */ static SubWorkflowGateway.Result failed(
      final SubWorkflowGateway.ChildFailed failure) {
    return new SubWorkflowGateway.Result(
        failure.executionId(), List.of(), failure.status(), null, failure.getMessage());
  }

  /**
   * The message emitted for a child: {@code {executionId, results, status, outcome?, reason?}}.
   *
   * @param message the message that started the child
   * @param result what the child produced
   * @return the message
   */
  /* default */ static Message<?> message(
      final Message<?> message, final SubWorkflowGateway.Result result) {
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("executionId", result.executionId());
    payload.put("results", result.results());
    payload.put("status", result.status());
    if (result.outcome() != null) {
      payload.put("outcome", result.outcome());
    }
    if (result.reason() != null) {
      payload.put("reason", result.reason());
    }
    return DefaultMessage.from(message, payload);
  }

  /**
   * The message emitted for a child, on the port named after how the child ended.
   *
   * @param message the message that started the child
   * @param result what the child produced
   * @return the message, with its source port set
   */
  /* default */ static Message<?> routed(
      final Message<?> message, final SubWorkflowGateway.Result result) {
    return message(message, result).withSourcePort(port(result));
  }

  private static String port(final SubWorkflowGateway.Result result) {
    final String port;
    if (!SUCCESS.equals(result.status()) || OUTCOME_FAILED.equals(result.outcome())) {
      port = PORT_FAILURE;
    } else if (OUTCOME_RETRY.equals(result.outcome())) {
      port = PORT_RETRY;
    } else if (OUTCOME_ESCALATE.equals(result.outcome())) {
      port = PORT_ESCALATE;
    } else {
      port = PORT_SUCCESS;
    }
    return port;
  }
}
