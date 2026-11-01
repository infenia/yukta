// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import org.jspecify.annotations.Nullable;

/**
 * One answer a request offers.
 *
 * @param id what an answer's decision must equal, such as {@code approve}
 * @param label what a person reads; the id when none is given
 * @since 0.1.0-alpha.4
 */
public record InputOption(String id, String label) {

  /**
   * Checks the id and fills in the label.
   *
   * @param id the option id, not blank
   * @param label the label, or {@code null} for the id
   */
  public InputOption(final String id, final @Nullable String label) {
    this.id = InputLimits.requireId("option id", id);
    this.label =
        label == null || label.isBlank()
            ? id
            : InputLimits.requireText("option label", label, InputLimits.ID_MAX_CHARS * 2);
  }

  /**
   * An option whose label is its id.
   *
   * @param id the option id
   */
  public InputOption(final String id) {
    this(id, null);
  }
}
