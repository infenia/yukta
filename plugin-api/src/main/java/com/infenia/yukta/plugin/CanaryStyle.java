// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package com.infenia.yukta.plugin;

import java.util.*;

/** Canary: Checkstyle star import and PMD unused private field. */
public final class CanaryStyle {
  private int unusedField;

  /** Returns a list. */
  public List<String> names() {
    return new ArrayList<>();
  }
}
