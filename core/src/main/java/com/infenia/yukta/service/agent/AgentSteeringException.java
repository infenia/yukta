// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import org.jspecify.annotations.Nullable;

/**
 * A command to an agent that was refused. The web layer answers with the status of the {@link
 * Reason} and its code, so a client can tell "not live" from "unsupported".
 */
public class AgentSteeringException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why a command was refused. */
  public enum Reason {
    /** The caller may not steer this conversation. */
    FORBIDDEN(403, "forbidden"),
    /** The conversation has ended or its agent does not run on this server. */
    NOT_LIVE(409, "not_live"),
    /** The agent lacks the capability the command needs. */
    UNSUPPORTED(409, "unsupported"),
    /** The message is longer than {@code yukta.agents.max-message-bytes}. */
    TOO_LARGE(413, "too_large"),
    /** The caller sent more than {@code yukta.agents.messages-per-minute} messages. */
    RATE_LIMITED(429, "rate_limited");

    private final int httpStatus;
    private final String errorCode;

    Reason(final int status, final String code) {
      this.httpStatus = status;
      this.errorCode = code;
    }

    /**
     * The HTTP status.
     *
     * @return the status
     */
    public int status() {
      return httpStatus;
    }

    /**
     * The code a client sees.
     *
     * @return the code
     */
    public String code() {
      return errorCode;
    }
  }

  private final Reason refusal;
  private final @Nullable String missing;

  /**
   * Creates the exception.
   *
   * @param reason why the command was refused
   * @param message what to tell the caller
   * @param capability the missing capability for {@link Reason#UNSUPPORTED}, else {@code null}
   */
  public AgentSteeringException(
      final Reason reason, final String message, final @Nullable String capability) {
    super(message);
    this.refusal = reason;
    this.missing = capability;
  }

  /**
   * Why the command was refused.
   *
   * @return the reason
   */
  public Reason reason() {
    return refusal;
  }

  /**
   * The capability the agent lacks.
   *
   * @return the capability, or {@code null}
   */
  public @Nullable String capability() {
    return missing;
  }
}
