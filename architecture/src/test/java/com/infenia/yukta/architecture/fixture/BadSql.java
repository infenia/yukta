// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

import java.sql.Timestamp;

/** Depends on java.sql; the self-test uses it to prove the forbidden-package rule fires. */
@SuppressWarnings("PMD")
public final class BadSql {
  private BadSql() {}

  /** Uses the forbidden package. */
  public static Timestamp now() {
    return new Timestamp(0L);
  }
}
