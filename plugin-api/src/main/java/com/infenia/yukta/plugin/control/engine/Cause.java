// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Why a directive was sent, stamped by the server and written to the audit: a reaction to an event,
 * the answer to a request, a timer, or the engine's own initiative.
 *
 * @param kind the kind of cause
 * @param id the event or request it answers, for {@link Kind#EVENT} and {@link Kind#ANSWERS}
 */
public record Cause(Kind kind, @Nullable String id) {

  /** The kinds of cause. */
  public enum Kind {
    /** A reaction to an event ({@code event:<id>}). */
    EVENT,
    /** The answer to a review or a delegation ({@code answers:<id>}). */
    ANSWERS,
    /** A timer ({@code tick}). */
    TICK,
    /** Anything else ({@code initiative}). */
    INITIATIVE
  }

  /** Checks that an event or an answer names what it reacts to. */
  public Cause {
    Objects.requireNonNull(kind, "kind");
    if ((kind == Kind.EVENT || kind == Kind.ANSWERS) && (id == null || id.isBlank())) {
      throw new IllegalArgumentException(kind + " needs the id it reacts to");
    }
  }

  /**
   * A reaction to an event.
   *
   * @param eventId the event
   * @return the cause
   */
  public static Cause event(final String eventId) {
    return new Cause(Kind.EVENT, eventId);
  }

  /**
   * The answer to a request.
   *
   * @param requestId the review or delegation
   * @return the cause
   */
  public static Cause answers(final String requestId) {
    return new Cause(Kind.ANSWERS, requestId);
  }

  /**
   * A timer.
   *
   * @return the cause
   */
  public static Cause tick() {
    return new Cause(Kind.TICK, null);
  }

  /**
   * The engine's own initiative.
   *
   * @return the cause
   */
  public static Cause initiative() {
    return new Cause(Kind.INITIATIVE, null);
  }

  /**
   * Whether nothing triggered it: a timer or the engine's own initiative, which need a reason and
   * the {@code initiative} allow-list.
   *
   * @return true for a tick or an initiative
   */
  public boolean unprompted() {
    return kind == Kind.TICK || kind == Kind.INITIATIVE;
  }

  /**
   * The text in the audit and in the contract: {@code event:<id>}, {@code answers:<id>}, {@code
   * tick} or {@code initiative}.
   *
   * @return the text
   */
  public String text() {
    return switch (kind) {
      case EVENT -> "event:" + id;
      case ANSWERS -> "answers:" + id;
      case TICK -> "tick";
      case INITIATIVE -> "initiative";
    };
  }
}
