// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

/** How a composite supervisor combines its children. */
public enum CompositeMode {
  /** The first child with a non-empty decision wins; later children are the fallback. */
  CHAIN,
  /** Like {@link #CHAIN}, but stops at the first child that returns any decision, even empty. */
  FIRST_MATCH,
  /** The children's command streams are merged; the arbiter settles conflicts. */
  PARALLEL,
  /** A command is sent when enough children emit an equal one within the window. */
  QUORUM;

  /**
   * Whether the children must decide per event, which only a control plane does.
   *
   * @return true for {@link #CHAIN} and {@link #FIRST_MATCH}
   */
  public boolean needsControlPlanes() {
    return this == CHAIN || this == FIRST_MATCH;
  }
}
