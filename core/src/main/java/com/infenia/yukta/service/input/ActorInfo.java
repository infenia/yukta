// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.security.ApiRole;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who sends a command, as the authoriser needs to know: the kind of sender and what its identity
 * allows. It is read from the kind the sender was authenticated as, never from the shape of the
 * actor string.
 *
 * @param type the kind of sender
 * @param id the key name, the supervisor id or the token tier; {@code anonymous} when no key is
 *     needed
 * @param role the role of a human, or {@code null}
 * @param sessions the only sessions a human's key reaches; empty means all, {@code null} for other
 *     kinds
 * @param capabilities what a supervisor may do, or {@code null}
 * @param scope the runs a supervisor watches, or {@code null}
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an immutable copy")
public record ActorInfo(
    Type type,
    String id,
    @Nullable ApiRole role,
    @Nullable Set<String> sessions,
    @Nullable SupervisorCapabilities capabilities,
    @Nullable SupervisorScope scope) {

  /** The kinds of sender. */
  public enum Type {

    /** A person or tool that used an API key. */
    HUMAN,

    /** A supervisor, attached or configured as a bean. */
    SUPERVISOR,

    /** A caller that holds a one-time answer token. */
    EXTERNAL,

    /** No identity: it has no rights. */
    UNAUTHENTICATED
  }

  /** Keeps an immutable copy of the sessions. */
  public ActorInfo {
    sessions = copyOf(sessions);
  }

  /**
   * A caller that used a key.
   *
   * @param id the key name
   * @param role the key's role
   * @param sessions the sessions the key is limited to; empty for all
   * @return the actor
   */
  public static ActorInfo human(final String id, final ApiRole role, final Set<String> sessions) {
    return new ActorInfo(Type.HUMAN, id, role, sessions, null, null);
  }

  /**
   * A supervisor.
   *
   * @param id the supervisor id
   * @param capabilities what it may do
   * @param scope the runs it watches
   * @return the actor
   */
  public static ActorInfo supervisor(
      final String id, final SupervisorCapabilities capabilities, final SupervisorScope scope) {
    return new ActorInfo(Type.SUPERVISOR, id, null, null, capabilities, scope);
  }

  /**
   * A caller with a one-time token.
   *
   * @param id the token tier
   * @return the actor
   */
  public static ActorInfo external(final String id) {
    return new ActorInfo(Type.EXTERNAL, id, null, null, null, null);
  }

  /**
   * A caller that did not identify itself, where identification is required.
   *
   * @return the actor, which has no rights
   */
  public static ActorInfo unauthenticated() {
    return new ActorInfo(Type.UNAUTHENTICATED, "unauthenticated", null, null, null, null);
  }

  private static @Nullable Set<String> copyOf(final @Nullable Set<String> sessions) {
    return sessions == null ? null : Set.copyOf(sessions);
  }
}
