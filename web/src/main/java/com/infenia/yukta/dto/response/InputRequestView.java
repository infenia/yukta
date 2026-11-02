// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An input request as the API shows it: what a node asks, how risky it is, where it stands and,
 * once answered, the answer. The prompt, the context and the advice were redacted when the request
 * was stored. They are written by the workflow or by an AI agent, so a client shows them as
 * untrusted text.
 *
 * @param id the request id
 * @param sessionId the session of the run
 * @param workflowId the workflow of the run
 * @param executionId the run that asks
 * @param nodeId the node that asks
 * @param state OPEN, ESCALATED, ANSWERED, EXPIRED or CANCELLED
 * @param version changes with every change of state; send it as {@code expectedVersion} to answer
 *     only the request as it was seen
 * @param kind APPROVAL, CHOICE, TEXT, STRUCTURED or QUERY
 * @param prompt the question (redacted, untrusted)
 * @param context what the node gives to decide with (redacted, untrusted)
 * @param options the choices, for APPROVAL and CHOICE
 * @param responseSchema the JSON schema a STRUCTURED answer must fit, or {@code null}
 * @param risk LOW, MEDIUM or HIGH
 * @param audience who may answer
 * @param onTimeout what happens at the deadline
 * @param defaultDecision the decision taken at the deadline with USE_DEFAULT, or {@code null}
 * @param requester who asks, as the node reported it
 * @param tier the tier that should answer now
 * @param advice what the last escalation suggested, or {@code null}
 * @param deadline when the request times out, or {@code null}
 * @param createdAt when it was asked
 * @param updatedAt when it last changed
 * @param answer the answer, once the request is answered and the answer is still kept
 */
@Schema(description = "An input request of a node and, once answered, its answer")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores unmodifiable copies")
@SuppressWarnings("PMD.ExcessiveParameterList") // one field per part of the request on the wire
public record InputRequestView(
    String id,
    String sessionId,
    String workflowId,
    String executionId,
    String nodeId,
    InputState state,
    long version,
    InputKind kind,
    String prompt,
    Map<String, Object> context,
    List<InputOptionView> options,
    @Nullable String responseSchema,
    InputRisk risk,
    Audience audience,
    TimeoutAction onTimeout,
    @Nullable String defaultDecision,
    String requester,
    String tier,
    @Nullable String advice,
    @Nullable Instant deadline,
    Instant createdAt,
    Instant updatedAt,
    @Nullable InputAnswerView answer) {

  /** Copies the collections. */
  public InputRequestView {
    // Map.copyOf rejects null values, and a value in the context may be null
    context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    options = List.copyOf(options);
  }

  /**
   * One choice of a request.
   *
   * @param id what an answer sends as its decision
   * @param label what a person reads
   */
  public record InputOptionView(String id, String label) {}

  /**
   * The answer to a request.
   *
   * @param decision what was decided
   * @param text free text that came with it, or {@code null}
   * @param data structured data that came with it
   * @param responderType SUPERVISOR, HUMAN, EXTERNAL or SYSTEM
   * @param responderId who answered, for example {@code key:alice} or a supervisor id
   * @param rationale why, or {@code null}
   * @param confidence how sure the responder was, from 0 to 1, or {@code null}
   * @param answeredAt when it was answered
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the compact constructor stores an unmodifiable copy")
  public record InputAnswerView(
      String decision,
      @Nullable String text,
      Map<String, Object> data,
      String responderType,
      String responderId,
      @Nullable String rationale,
      @Nullable Double confidence,
      Instant answeredAt) {

    /** Copies the data. */
    public InputAnswerView {
      data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * Shows a stored answer.
     *
     * @param response the answer
     * @return the view
     */
    public static InputAnswerView of(final InputResponse response) {
      return new InputAnswerView(
          response.decision(),
          response.text(),
          response.data(),
          response.responder().type().name(),
          response.responder().id(),
          response.rationale(),
          response.confidence(),
          response.answeredAt());
    }
  }

  /**
   * Shows a request and its answer.
   *
   * @param request the request
   * @param response its answer, or {@code null}
   * @return the view
   */
  public static InputRequestView of(
      final InputRequest request, final @Nullable InputResponse response) {
    final InputRequestSpec spec = request.spec();
    return new InputRequestView(
        request.id(),
        request.sessionId(),
        request.workflowId(),
        request.executionId(),
        request.nodeId(),
        request.state(),
        request.version(),
        spec.kind(),
        spec.prompt(),
        spec.context(),
        spec.options().stream().map(InputRequestView::option).toList(),
        spec.responseSchema(),
        spec.risk(),
        spec.audience(),
        spec.onTimeout(),
        spec.defaultDecision(),
        spec.requester(),
        request.tier(),
        request.advice(),
        request.deadline(),
        request.createdAt(),
        request.updatedAt(),
        response == null ? null : InputAnswerView.of(response));
  }

  private static InputOptionView option(final InputOption option) {
    return new InputOptionView(option.id(), option.label());
  }
}
