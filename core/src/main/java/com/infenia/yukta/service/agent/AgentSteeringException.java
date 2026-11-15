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

    private final int status;
    private final String code;

    Reason(final int status, final String code) {
      this.status = status;
      this.code = code;
    }

    /**
     * The HTTP status.
     *
     * @return the status
     */
    public int status() {
      return status;
    }

    /**
     * The code a client sees.
     *
     * @return the code
     */
    public String code() {
      return code;
    }
  }

  private final Reason reason;
  private final @Nullable String capability;

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
    this.reason = reason;
    this.capability = capability;
  }

  /**
   * Why the command was refused.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * The capability the agent lacks.
   *
   * @return the capability, or {@code null}
   */
  public @Nullable String capability() {
    return capability;
  }
}
