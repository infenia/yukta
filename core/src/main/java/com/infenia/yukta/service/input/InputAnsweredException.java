// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.service.ConflictException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * An answer or an escalation that came after the request was already answered (HTTP 409). It names
 * what the stored answer decided and who gave it, never its text or data.
 */
public final class InputAnsweredException extends ConflictException {

  @Serial private static final long serialVersionUID = 1L;

  /** The request. */
  private final String request;

  /** What the stored answer decided, or {@code null} when the answer is no longer kept. */
  private final @Nullable String storedDecision;

  /** Who gave it, as {@code TYPE:id}, or {@code null} when the answer is no longer kept. */
  private final @Nullable String storedResponder;

  /**
   * Creates the exception.
   *
   * @param requestId the request
   * @param decision what the stored answer decided, or {@code null}
   * @param responder who gave it, as {@code TYPE:id}, or {@code null}
   */
  public InputAnsweredException(
      final String requestId, final @Nullable String decision, final @Nullable String responder) {
    super(
        "Input request "
            + requestId
            + " is already answered"
            + (decision == null ? "" : ": " + decision + " by " + responder));
    this.request = requestId;
    this.storedDecision = decision;
    this.storedResponder = responder;
  }

  /**
   * The request.
   *
   * @return the request id
   */
  public String requestId() {
    return request;
  }

  /**
   * What the stored answer decided.
   *
   * @return the decision, or {@code null} when the answer is no longer kept
   */
  public @Nullable String decision() {
    return storedDecision;
  }

  /**
   * Who gave the stored answer.
   *
   * @return {@code TYPE:id}, for example {@code HUMAN:key:alice}, or {@code null}
   */
  public @Nullable String responder() {
    return storedResponder;
  }
}
