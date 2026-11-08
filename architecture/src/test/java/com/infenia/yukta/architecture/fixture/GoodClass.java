// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

/** Breaks no rule; the self-test expects every rule to accept it. */
@SuppressWarnings("PMD")
public final class GoodClass {
  private final String name;

  /** Creates the object. */
  public GoodClass(final String name) {
    this.name = name;
  }

  /** The name. */
  public String name() {
    return name;
  }
}
