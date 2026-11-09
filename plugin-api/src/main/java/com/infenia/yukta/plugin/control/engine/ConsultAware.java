// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

/** A supervisor that passes questions up to its parents; the server tells it how to ask. */
@FunctionalInterface
public interface ConsultAware {

  /**
   * Gives the supervisor the way to ask its parents.
   *
   * @param consultant asks the parents
   */
  void consultWith(Consultant consultant);
}
