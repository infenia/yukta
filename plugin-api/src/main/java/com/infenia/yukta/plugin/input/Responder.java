// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.util.Objects;

/**
 * Who answered a request.
 *
 * @param type what kind of responder it was
 * @param id the supervisor id, the API key name, the external tier, or {@code system}
 * @since 0.1.0-alpha.4
 */
public record Responder(Type type, String id) {

  /** The responder of a default decision taken at a timeout. */
  public static final Responder SYSTEM = new Responder(Type.SYSTEM, "system");

  /** The kinds of responder. */
  public enum Type {

    /** A supervisor, rule driven, model driven or a workflow. */
    SUPERVISOR,

    /** A person, known by the API key they used. */
    HUMAN,

    /** An external system or agent that answered through a tier's one-time token. */
    EXTERNAL,

    /** The engine itself, for a default decision taken at a timeout. */
    SYSTEM
  }

  /**
   * Checks both parts.
   *
   * @param type the kind
   * @param id the responder id
   */
  public Responder {
    Objects.requireNonNull(type, "type");
    InputLimits.requireId("responder id", id);
  }
}
