// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What an engine receives, always limited to what its supervisor owns. */
public sealed interface Signal {

  /** The names of the signal kinds, as the contract lists them. */
  enum Kind {
    /** An execution event. */
    EVENT,
    /** A timer. */
    TICK,
    /** A command someone wants to send. */
    REVIEW,
    /** A delegation from a child. */
    DELEGATED,
    /** The answers of the parents to a question this engine passed up. */
    CONSULTED,
    /** The engine started or reconnected. */
    STARTED
  }

  /**
   * The kind of signal.
   *
   * @return the kind
   */
  Kind kind();

  /**
   * An execution event.
   *
   * @param id the id a directive can name as its cause
   * @param cursor where the stream is, to resume after
   * @param event the event
   */
  record Event(String id, String cursor, ExecutionEvent event) implements Signal {
    @Override
    public Kind kind() {
      return Kind.EVENT;
    }
  }

  /**
   * Time, only when the engine's settings ask for a period.
   *
   * @param at when
   */
  record Tick(Instant at) implements Signal {
    @Override
    public Kind kind() {
      return Kind.TICK;
    }
  }

  /**
   * A command that a person or the operator gateway wants to send for a unit the supervisor owns.
   * The engine answers with a {@link Directive.ReviewVerdict} within the timeout.
   *
   * @param id the review id
   * @param request the command and who sent it
   * @param timeout how long the engine has
   */
  record Review(String id, ControlRequest request, Duration timeout) implements Signal {
    @Override
    public Kind kind() {
      return Kind.REVIEW;
    }
  }

  /**
   * A delegation from a child. The engine answers with a {@link Directive.DelegationAnswer}.
   *
   * @param id the delegation id
   * @param delegation what is delegated
   * @param fromSupervisor the child
   * @param payload the content
   */
  record Delegated(
      String id, DelegationKind delegation, String fromSupervisor, Map<String, Object> payload)
      implements Signal {

    /** Keeps an unmodifiable copy. */
    public Delegated {
      payload = Map.copyOf(payload);
    }

    @Override
    public Kind kind() {
      return Kind.DELEGATED;
    }
  }

  /**
   * What the parents answered to a {@link Directive.Delegate} of this engine. A parent that did not
   * answer in time is missing; an empty map means no parent had an opinion.
   *
   * @param id the key the engine gave its {@code Delegate}, or a generated id when it gave none
   * @param delegation what was delegated
   * @param answers the typed answer of each parent that gave one, by parent supervisor id
   */
  record Consulted(String id, DelegationKind delegation, Map<String, Map<String, Object>> answers)
      implements Signal {

    /** Keeps an unmodifiable copy. */
    public Consulted {
      answers = Map.copyOf(answers);
    }

    @Override
    public Kind kind() {
      return Kind.CONSULTED;
    }
  }

  /**
   * The engine started or reconnected, with what it needs to catch up.
   *
   * @param reason {@code first}, {@code resubscribe} or {@code credential-rotated}
   * @param cursor where the event stream continues from
   * @param executions the owned executions, as {@code id:status}
   * @param openInputs the ids of the open input requests of owned units
   * @param held the executions this supervisor paused, which {@code resume held} resumes
   */
  record Started(
      String reason,
      String cursor,
      List<String> executions,
      List<String> openInputs,
      List<String> held)
      implements Signal {

    /** Keeps unmodifiable copies. */
    public Started {
      executions = List.copyOf(executions);
      openInputs = List.copyOf(openInputs);
      held = List.copyOf(held);
    }

    @Override
    public Kind kind() {
      return Kind.STARTED;
    }
  }
}
