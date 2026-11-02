// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An input request that waits for an answer, as a client lists it. It carries the redacted prompt
 * and the options, never the context.
 *
 * @param requestId the request id
 * @param sessionId the session of the run
 * @param executionId the run that asked
 * @param workflowId the workflow of the run
 * @param nodeId the node that waits
 * @param kind what kind of answer is wanted
 * @param prompt the question, redacted
 * @param options the options of a choice or an approval
 * @param risk the risk of the decision
 * @param tier the tier that may answer now
 * @param state {@code OPEN} or {@code ESCALATED}
 * @param version the version to send back as {@code expectedVersion}
 * @param since when it was asked
 * @param deadline when it ends, or {@code null}
 */
public record PendingInput(
    String requestId,
    String sessionId,
    String executionId,
    String workflowId,
    String nodeId,
    InputKind kind,
    String prompt,
    List<InputOption> options,
    InputRisk risk,
    String tier,
    InputState state,
    long version,
    Instant since,
    @Nullable Instant deadline) {

  /** Keeps an immutable copy of the options. */
  public PendingInput {
    options = List.copyOf(options);
  }

  /**
   * Lists a request.
   *
   * @param request the request
   * @return how a client sees it
   */
  public static PendingInput from(final InputRequest request) {
    return new PendingInput(
        request.id(),
        request.sessionId(),
        request.executionId(),
        request.workflowId(),
        request.nodeId(),
        request.spec().kind(),
        request.spec().prompt(),
        request.spec().options(),
        request.spec().risk(),
        request.tier(),
        request.state(),
        request.version(),
        request.createdAt(),
        request.deadline());
  }
}
