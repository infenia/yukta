// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

/** A supervisor that is given what it may read when it is attached. */
@FunctionalInterface
public interface ReadsAware {

  /**
   * Gives the supervisor its reads, limited to what it owns.
   *
   * @param reads the reads
   */
  void readsFrom(EngineReads reads);
}
