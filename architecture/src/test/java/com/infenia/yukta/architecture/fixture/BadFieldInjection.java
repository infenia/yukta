// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

import org.springframework.beans.factory.annotation.Autowired;

/** Uses field injection; the self-test expects the rule to object. */
@SuppressWarnings("PMD")
public final class BadFieldInjection {
  @Autowired private String dependency;

  /** Reads the field. */
  public String dependency() {
    return dependency;
  }
}
