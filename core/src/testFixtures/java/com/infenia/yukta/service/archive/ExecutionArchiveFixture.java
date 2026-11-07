// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.archive;

/**
 * Short forms of {@link ExecutionArchive} for tests: the shapes its extra constructors used to
 * offer.
 */
public final class ExecutionArchiveFixture {

  private ExecutionArchiveFixture() {}

  /** A ExecutionArchive with the default for what the parameters leave out. */
  public static ExecutionArchive create() {
    return new ExecutionArchive(10);
  }
}
