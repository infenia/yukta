// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** Names the Reactor context key under which the sender of a control command is passed. */
public final class ControlActor {

  /** Context key; the value is the sender's id, a {@link String}. */
  public static final String CONTEXT_KEY = "yukta.control.actor";

  private ControlActor() {}
}
