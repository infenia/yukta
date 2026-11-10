// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an engine returns. The supervisor checks each directive before it has any effect; the engine
 * never sees the checks, only the outcome the contract promises.
 */
public sealed interface Directive {

  /**
   * A control command. A command caused by a tick or by the engine's own initiative needs a {@code
   * reason}.
   *
   * @param key the idempotency key; a repeat returns the first outcome
   * @param command the command, with the target it addresses
   * @param cause why it is sent
   * @param reason a short reason, required for a tick or an initiative
   * @param ifStatus a status the target must have, or {@code null}
   */
  record Command(
      String key,
      ExecutionControlCommand command,
      Cause cause,
      @Nullable String reason,
      @Nullable String ifStatus)
      implements Directive {

    /** Checks the parts. */
    public Command {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(cause, "cause");
    }
  }

  /** The verdict on a command someone wants to send for a unit the supervisor owns. */
  enum Verdict {
    /** Let it run. */
    ALLOW,
    /** Refuse it; final. */
    DENY,
    /** Hold it until a parent or a person approves. */
    REQUIRE_APPROVAL
  }

  /**
   * The answer to a review request.
   *
   * @param answers the review it answers
   * @param verdict the verdict
   * @param reason why, for a refusal
   */
  record ReviewVerdict(String answers, Verdict verdict, @Nullable String reason)
      implements Directive {}

  /**
   * The answer to a delegation from a child.
   *
   * @param answers the delegation it answers
   * @param answer the typed answer
   */
  record DelegationAnswer(String answers, Map<String, Object> answer) implements Directive {

    /** Keeps an unmodifiable copy. */
    public DelegationAnswer {
      answer = Map.copyOf(answer);
    }
  }

  /**
   * A question or request passed up to the parents. The parents' answers come back to the engine as
   * a {@link Signal.Consulted} carrying the same key.
   *
   * @param kind what is delegated
   * @param payload the content
   * @param key names the question in the answer, or {@code null} for a generated id
   */
  record Delegate(DelegationKind kind, Map<String, Object> payload, @Nullable String key)
      implements Directive {

    /** Keeps an unmodifiable copy. */
    public Delegate {
      payload = Map.copyOf(payload);
    }

    /**
     * A question with no key of its own.
     *
     * @param kind what is delegated
     * @param payload the content
     */
    public Delegate(final DelegationKind kind, final Map<String, Object> payload) {
      this(kind, payload, null);
    }
  }

  /**
   * An audited message that changes nothing.
   *
   * @param message what to record
   * @param cause why
   * @param reason a short reason, required for a tick or an initiative
   */
  record Notify(String message, Cause cause, @Nullable String reason) implements Directive {}

  /**
   * Explicitly no answer.
   *
   * @param answers the request it declines to answer
   */
  record NoOpinion(String answers) implements Directive {}
}
