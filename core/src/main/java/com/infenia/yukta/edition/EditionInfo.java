// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.edition;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What this build is: its edition, licence and features. The default is {@link Edition#COMMUNITY};
 * the Plus module replaces it. Shown by {@code GET /api/edition} and {@code /actuator/info}.
 *
 * @param edition the edition
 * @param license the SPDX licence expression of the build
 * @param features the optional features present, such as {@code file-stores}
 * @param source where the corresponding source can be obtained, or null
 */
public record EditionInfo(
    Edition edition, String license, List<String> features, @Nullable String source) {

  /** Copies the feature list. */
  public EditionInfo {
    features = List.copyOf(features);
  }

  /**
   * The Community build.
   *
   * @return the default info
   */
  public static EditionInfo community() {
    return new EditionInfo(Edition.COMMUNITY, "Apache-2.0", List.of(), null);
  }
}
