// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import lombok.experimental.UtilityClass;

/** Names the Reactor context key under which the sender of a control command is passed. */
@UtilityClass
public class ControlActor {

  /** Context key; the value is the sender's id, a {@link String}. */
  public static final String CONTEXT_KEY = "yukta.control.actor";

  /**
   * Context key of what kind of sender the actor is, a {@link Kind}. An authorisation decision
   * reads this and never the shape of the actor string, because a supervisor id can look like an
   * API key name.
   */
  public static final String KIND_KEY = "yukta.control.actor.kind";

  /** The kinds of sender. */
  public enum Kind {

    /** A supervisor; the actor is its id. */
    SUPERVISOR,

    /** A caller that used an API key; the actor is {@code key:<name>}. */
    KEY,

    /** A caller that used a one-time answer token; the actor is {@code token:<tier>}. */
    TOKEN
  }
}
